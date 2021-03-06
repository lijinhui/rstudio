/*
 * RemoteServer.java
 *
 * Copyright (C) 2009-11 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.server.remote;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.http.client.URL;
import com.google.gwt.json.client.*;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.rstudio.core.client.Debug;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.core.client.jsonrpc.*;
import org.rstudio.studio.client.application.Desktop;
import org.rstudio.studio.client.application.events.*;
import org.rstudio.studio.client.application.model.HttpLogEntry;
import org.rstudio.studio.client.common.codetools.Completions;
import org.rstudio.studio.client.server.*;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.remote.RemoteServerEventListener.ClientEvent;
import org.rstudio.studio.client.workbench.model.Agreement;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.SessionInfo;
import org.rstudio.studio.client.workbench.model.WorkbenchMetrics;
import org.rstudio.studio.client.workbench.prefs.model.RPrefs;
import org.rstudio.studio.client.workbench.views.files.model.FileUploadToken;
import org.rstudio.studio.client.workbench.views.help.model.HelpInfo;
import org.rstudio.studio.client.workbench.views.help.model.Link;
import org.rstudio.studio.client.workbench.views.history.model.HistoryEntry;
import org.rstudio.studio.client.workbench.views.packages.model.PackageInfo;
import org.rstudio.studio.client.workbench.views.plots.model.Point;
import org.rstudio.studio.client.workbench.views.source.model.CheckForExternalEditResult;
import org.rstudio.studio.client.workbench.views.source.model.PublishPdfResult;
import org.rstudio.studio.client.workbench.views.source.model.SourceDocument;
import org.rstudio.studio.client.workbench.views.workspace.model.DataPreviewResult;
import org.rstudio.studio.client.workbench.views.workspace.model.DownloadInfo;
import org.rstudio.studio.client.workbench.views.workspace.model.GoogleSpreadsheetImportSpec;
import org.rstudio.studio.client.workbench.views.workspace.model.GoogleSpreadsheetInfo;
import org.rstudio.studio.client.workbench.views.workspace.model.WorkspaceObjectInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class RemoteServer implements Server
{ 
   @Inject
   public RemoteServer(Session session, EventBus eventBus)
   {
      clientId_ = null;
      disconnected_ = false;
      workbenchReady_ = false;
      session_ = session;
      eventBus_ = eventBus;
      serverAuth_ = new RemoteServerAuth(this);
      serverEventListener_ = new RemoteServerEventListener(this);
   }
   
   // complete initialization now that the workbench is ready
   public void onWorkbenchReady()
   {
      // update state
      workbenchReady_ = true;
      
      // only check credentials if we are in server mode
      if (session_.getSessionInfo().getMode().equals(SessionInfo.SERVER_MODE))
         serverAuth_.schedulePeriodicCredentialsUpdate();
      
      // start event listener
      serverEventListener_.start();
   }
   
   public void ensureListeningForEvents()
   {
      // if the workbench is ready then make sure we are listening for
      // events (retry events up to 10 times). 
      
      // we need to check for workbenchReady_ because we don't want to cause
      // events to flow prior to the workbench being instantiated and fully 
      // initialized. since this method can be called at any time we need to
      // protect ourselves against this "pre-workbench initialization" state
      
      // the retries are there to work around the fact that when we execute a
      // network request which causes us to resume from a suspended session
      // the first query for events often returns ServiceUnavailable because 
      // the process isn't alive yet. by retrying we make certain that if
      // the first attempts to listen fail we eventually get synced up
      
      if (workbenchReady_)
         serverEventListener_.ensureListening(10);
   }
   
   public void log(int logEntryType, 
                   String logEntry, 
                   ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(logEntryType));
      params.set(1, new JSONString(logEntry));
      sendRequest(LOG_SCOPE , LOG, params, requestCallback);
   }
    
   public void clientInit(
                     final ServerRequestCallback<SessionInfo> requestCallback)
   {      
      // send init request (record clientId and version contained in response)
      sendRequest(RPC_SCOPE, 
                  CLIENT_INIT, 
                  new ServerRequestCallback<SessionInfo>() {

         public void onResponseReceived(SessionInfo sessionInfo)
         {
            clientId_ = sessionInfo.getClientId();
            clientVersion_ = sessionInfo.getClientVersion();
            requestCallback.onResponseReceived(sessionInfo);
         }
   
         public void onError(ServerError error)
         {
            requestCallback.onError(error);
         }
      });
   }
   
   // accept application agreement
   public void acceptAgreement(Agreement agreement, 
                               ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  ACCEPT_AGREEMENT, 
                  agreement.getHash(),
                  requestCallback);
   }
   
   
   public void suspendSession(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, SUSPEND_SESSION, requestCallback);
   }
   
   public void quitSession(boolean saveWorkspace, 
                           ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, QUIT_SESSION, saveWorkspace, requestCallback);
   }
   
   public void updateCredentials()
   {
      serverAuth_.attemptToUpdateCredentials();
   }
   
   public String getApplicationURL(String pathName)
   {
      // if accessing a URL is the first thing we do after being
      // suspended ensure that events flow right away
      ensureListeningForEvents();
      
      // return the url
      return GWT.getHostPageBaseURL() + pathName;
   }
  
   
   public void setWorkbenchMetrics(WorkbenchMetrics metrics,
                                   ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  SET_WORKBENCH_METRICS, 
                  metrics, 
                  requestCallback);
   }

   public void setUiPrefs(JavaScriptObject uiPrefs,
                          ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  SET_UI_PREFS,
                  uiPrefs,
                  requestCallback);
   }

   public void getRPrefs(ServerRequestCallback<RPrefs> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_R_PREFS,
                  requestCallback);
   }

   public void setRPrefs(int saveAction,
                         boolean loadRData,
                         String initialWorkingDirectory,
                         ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(saveAction));
      params.set(1, JSONBoolean.getInstance(loadRData));
      params.set(2, new JSONString(initialWorkingDirectory));

      sendRequest(RPC_SCOPE,
                  SET_R_PREFS,
                  params,
                  requestCallback);
   }

   public void updateClientState(JavaScriptObject temporary,
                                 JavaScriptObject persistent,
                                 ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONObject(temporary));
      params.set(1, new JSONObject(persistent));
      sendRequest(RPC_SCOPE,
                  SET_CLIENT_STATE,
                  params,
                  requestCallback);
   }
   
   public void userPromptCompleted(int response, 
                                  ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, USER_PROMPT_COMPLETED, response, requestCallback);
   }

   public void consoleInput(String consoleInput,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CONSOLE_INPUT, consoleInput, requestCallback);
   }
   
   public void resetConsoleActions(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, RESET_CONSOLE_ACTIONS, requestCallback);
   }
   
   public void interrupt(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, INTERRUPT, requestCallback);
   }
   
   public void abort(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, ABORT, requestCallback);
   }
   
   public void httpLog(
         ServerRequestCallback<JsArray<HttpLogEntry>> requestCallback)
   {
      sendRequest(RPC_SCOPE, HTTP_LOG, requestCallback);
   }

   public void getCompletions(String line, int cursorPos,
                          ServerRequestCallback<Completions> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(line));
      params.set(1, new JSONNumber(cursorPos));
      sendRequest(RPC_SCOPE, 
                  GET_COMPLETIONS, 
                  params, 
                  requestCallback) ;
   }
   
   public void listObjects(
         ServerRequestCallback<RpcObjectList<WorkspaceObjectInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_OBJECTS, requestCallback);
   }

  
   public void removeAllObjects(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  REMOVE_ALL_OBJECTS,
                  requestCallback);
   }

   
   public void setObjectValue(String objectName,
                              String value,
                              ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(objectName));
      params.set(1, new JSONString(value));
      sendRequest(RPC_SCOPE,
                  SET_OBJECT_VALUE,
                  params,
                  requestCallback);
   }

   public void getObjectValue(String objectName,
                              ServerRequestCallback<RpcObjectList<WorkspaceObjectInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_OBJECT_VALUE,
                  objectName,
                  requestCallback);
   }

   public void saveWorkspace(String filename,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  SAVE_WORKSPACE,
                  filename,
                  requestCallback);
   }
   
   public void loadWorkspace(String filename,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  LOAD_WORKSPACE,
                  filename,
                  requestCallback);
   }
   
   public void listGoogleSpreadsheets(
         String titlePattern,             // null for all spreadsheets
         int maxResults,
         ServerRequestCallback<JsArray<GoogleSpreadsheetInfo>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, titlePattern != null ? new JSONString(titlePattern) :
                                           JSONNull.getInstance());
      params.set(1, new JSONNumber(maxResults));
      sendRequest(RPC_SCOPE, 
                  LIST_GOOGLE_SPREADSHEETS, 
                  params, 
                  requestCallback) ;
   }
   
   public void importGoogleSpreadsheet(
                                GoogleSpreadsheetImportSpec importSpec,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  IMPORT_GOOGLE_SPREADSHEET, 
                  importSpec, 
                  requestCallback);
   }
   
   public void downloadDataFile(
                  String dataFileUrl,
                  ServerRequestCallback<DownloadInfo> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  DOWNLOAD_DATA_FILE,
                  dataFileUrl,
                  requestCallback);
   }

   public void getDataPreview(String dataFilePath,
                              ServerRequestCallback<DataPreviewResult> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_DATA_PREVIEW,
                  dataFilePath,
                  requestCallback);
   }

   public void getOutputPreview(String dataFilePath,
                                boolean heading,
                                String separator,
                                String quote,
                                ServerRequestCallback<DataPreviewResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(dataFilePath));
      params.set(1, JSONBoolean.getInstance(heading));
      params.set(2, new JSONString(separator));
      params.set(3, new JSONString(quote));

      sendRequest(RPC_SCOPE,
                  GET_OUTPUT_PREVIEW,
                  params,
                  requestCallback);
   }

   public void editCompleted(String text,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, EDIT_COMPLETED, text, requestCallback);
   }
   
   public void chooseFileCompleted(String file, 
                                   ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CHOOSE_FILE_COMPLETED, file, requestCallback);
   }


   public void listPackages(
         ServerRequestCallback<JsArray<PackageInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_PACKAGES, requestCallback);
   }
   
   public void availablePackages(
         String repository,
         ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, AVAILABLE_PACKAGES, repository, requestCallback);
   }

   public void loadPackage(String packageName,
                           ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, LOAD_PACKAGE, packageName, requestCallback); 
   }

   public void unloadPackage(String packageName,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, UNLOAD_PACKAGE, packageName, requestCallback);
   }

   public void isCRANConfigured(ServerRequestCallback<Boolean> requestCallback)
   {
      sendRequest(RPC_SCOPE, IS_CRAN_CONFIGURED, requestCallback);
   }

   public void setCRANReposUrl(String reposUrl,
                               ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, SET_CRAN_REPOS_URL, reposUrl, requestCallback);
   }

   public void suggestTopics(String prefix,
                             ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, "suggest_topics", prefix, requestCallback);
   }

   public void getHelp(String topic,
                       String packageName,
                       int options,
                       ServerRequestCallback<HelpInfo> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(topic));
      if (packageName != null)
         params.set(1, new JSONString(packageName));
      else
         params.set(1, JSONNull.getInstance());
      params.set(2, new JSONNumber(options));
      
      sendRequest(RPC_SCOPE, GET_HELP, params, requestCallback);
   }
   
   public String getHelpUrl(String topicURI)
   {
      String helpUrl = getApplicationURL(HELP_SCOPE) + "/" + topicURI;
      return helpUrl;
   }
   
   public void showHelpTopic(String topic, String pkgName)
   {
      JSONArray params = new JSONArray() ;
      params.set(0, new JSONString(topic)) ;
      params.set(1, pkgName != null 
                       ? new JSONString(pkgName)
                       : JSONNull.getInstance()) ;
      
      sendRequest(RPC_SCOPE,
                  SHOW_HELP_TOPIC,
                  params,
                  null) ;
   }
   
   public void search(String query, 
                      ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  SEARCH,
                  query,
                  requestCallback) ;
   }
   
   public void getHelpLinks(String setName, 
                            ServerRequestCallback<LinksList> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_HELP_LINKS,
                  setName,
                  requestCallback) ;
   }
   
   public void setHelpLinks(String setName, ArrayList<Link> links)
   {
      JSONArray urls = new JSONArray() ;
      JSONArray titles = new JSONArray() ;
      for (int i = 0; i < links.size(); i++)
      {
         urls.set(i, new JSONString(links.get(i).getUrl())) ;
         titles.set(i, new JSONString(links.get(i).getTitle())) ;
      }
      
      JSONArray params = new JSONArray() ;
      params.set(0, new JSONString(setName)) ;
      params.set(1, urls) ;
      params.set(2, titles) ;
      
      sendRequest(RPC_SCOPE,
                  SET_HELP_LINKS,
                  params,
                  null) ;
   }

   public void listFiles(
                  FileSystemItem directory,
                  boolean monitor,
                  ServerRequestCallback<JsArray<FileSystemItem>> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(directory.getPath()));
      paramArray.set(1, JSONBoolean.getInstance(monitor));
      
      sendRequest(RPC_SCOPE, 
                  LIST_FILES, 
                  paramArray, 
                  requestCallback);    
   }

   public void listAllFiles(String path,
                            String pattern,
                            ServerRequestCallback<JsArrayString> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONString(pattern));
      sendRequest(RPC_SCOPE,
                  LIST_ALL_FILES,
                  params,
                  requestCallback);
   }

   public void createFile(FileSystemItem file,
                          ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CREATE_FILE, file.getPath(), requestCallback);
   }

   public void createFolder(FileSystemItem folder,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  CREATE_FOLDER, 
                  folder.getPath(), 
                  requestCallback);
   }

   public void deleteFiles(ArrayList<FileSystemItem> files,
                           ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      JSONArray pathArray = new JSONArray();
      for (int i=0; i<files.size(); i++)
         pathArray.set(i, new JSONString(files.get(i).getPath()));
      paramArray.set(0, pathArray);

      sendRequest(RPC_SCOPE, DELETE_FILES, paramArray, requestCallback);
   }
   
   public void copyFile(FileSystemItem sourceFile,
                        FileSystemItem targetFile,
                        ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(sourceFile.getPath()));
      paramArray.set(1, new JSONString(targetFile.getPath()));
      
      sendRequest(RPC_SCOPE, COPY_FILE, paramArray, requestCallback);
   }


   public void moveFiles(ArrayList<FileSystemItem> files,
                         FileSystemItem targetDirectory,
                         ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();

      JSONArray pathArray = new JSONArray();
      for (int i=0; i<files.size(); i++)
         pathArray.set(i, new JSONString(files.get(i).getPath()));

      paramArray.set(0, pathArray);
      paramArray.set(1, new JSONString(targetDirectory.getPath()));

      sendRequest(RPC_SCOPE, MOVE_FILES, paramArray, requestCallback);
   }

   public void renameFile(FileSystemItem file, 
                          FileSystemItem targetFile,
                          ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(file.getPath()));
      paramArray.set(1, new JSONString(targetFile.getPath()));

      sendRequest(RPC_SCOPE, RENAME_FILE, paramArray, requestCallback);
   }

   public String getFileUrl(FileSystemItem file)
   {
      if (Desktop.isDesktop())
      {
         return Desktop.getFrame().getUriForPath(file.getPath());
      }
      
      if (!file.isDirectory())
      {
         if (file.isWithinHome())
         {
            return getApplicationURL(FILES_SCOPE) + "/" + file.homeRelativePath();
         }
         else
         {
            String url = getApplicationURL(FILE_SHOW);
            url += "?path=" + URL.encodeComponent(file.getPath(), true);
            return url;
         }  
      }
      else
      {
         return null;
      }
   }
   
   // get file upload base url
   public String getFileUploadUrl()
   {
      return getApplicationURL(UPLOAD_SCOPE);
   }
      
   public void completeUpload(FileUploadToken token,
                              boolean commit,
                              ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONObject(token));
      paramArray.set(1, JSONBoolean.getInstance(commit));
      sendRequest(RPC_SCOPE, COMPLETE_UPLOAD, paramArray, requestCallback);
   }
   
   public String getFileExportUrl(String name, FileSystemItem file)
   {
      return getApplicationURL(EXPORT_SCOPE) + "?" +
         "name=" + URL.encodeComponent(name, true) + "&" +
         "file=" + URL.encodeComponent(file.getPath(), true);
   }
   
   
   public String getFileExportUrl(String name,
                                  FileSystemItem parentDirectory,
                                  ArrayList<String> filenames)
   {
      // build url params for files
      StringBuilder files = new StringBuilder();
      for (int i = 0; i<filenames.size(); i++)
      {
         files.append("file" + i + "=");
         files.append(URL.encodeComponent(filenames.get(i), true));
         files.append("&");
      }
         
      // return url
      return getApplicationURL(EXPORT_SCOPE) + "?" +
        "name=" + URL.encodeComponent(name, true) + "&" +
        "parent=" + URL.encodeComponent(parentDirectory.getPath(), true) + "&" +
         files.toString();
   }
   
   
   // get graphics url
   public String getGraphicsUrl(String filename)
   {
      return getApplicationURL(GRAPHICS_SCOPE) + "/" + filename;
   }
   
   public void nextPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, NEXT_PLOT, requestCallback);
   }
   
   public void previousPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, PREVIOUS_PLOT, requestCallback);
   }
   
   public void clearPlots(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLEAR_PLOTS, requestCallback);
   }
   
   public void refreshPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, REFRESH_PLOT, requestCallback);
   }

   public void exportPlot(FileSystemItem file,
                          int width,
                          int height,
                          ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(file.getPath()));
      params.set(1, new JSONNumber(width));
      params.set(2, new JSONNumber(height));
      sendRequest(RPC_SCOPE, EXPORT_PLOT, params, requestCallback);
   }

   public void locatorCompleted(Point point,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, LOCATOR_COMPLETED, point, requestCallback);
   }
   
   public void setManipulatorValues(JSONObject values,
                                    ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, values);
      sendRequest(RPC_SCOPE, SET_MANIPULATOR_VALUES, params, requestCallback);
   }

   public void newDocument(String filetype,
                           JsObject properties,
                           ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(filetype));
      params.set(1, new JSONObject(properties));
      sendRequest(RPC_SCOPE, NEW_DOCUMENT, params, requestCallback);
   }

   public void openDocument(String path,
                            String filetype,
                            ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONString(filetype));
      sendRequest(RPC_SCOPE, OPEN_DOCUMENT, params, requestCallback);
   }

   public void listDocuments(
         ServerRequestCallback<JsArray<SourceDocument>> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_DOCUMENTS, requestCallback);
   }

   public void saveDocument(String id,
                            String path,
                            String fileType,
                            String contents,
                            ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, path == null ? JSONNull.getInstance() : new JSONString(path));
      params.set(2, fileType == null ? JSONNull.getInstance() : new JSONString(fileType));
      params.set(3, new JSONString(contents));
      sendRequest(RPC_SCOPE, SAVE_DOCUMENT, params, requestCallback);
   }

   public void saveDocumentDiff(String id,
                                String path,
                                String fileType, String replacement,
                                int offset,
                                int length,
                                String hash,
                                ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, path == null ? JSONNull.getInstance() : new JSONString(path));
      params.set(2, fileType == null ? JSONNull.getInstance() : new JSONString(fileType));
      params.set(3, new JSONString(replacement));
      params.set(4, new JSONNumber(offset));
      params.set(5, new JSONNumber(length));
      params.set(6, new JSONString(hash));
      sendRequest(RPC_SCOPE, SAVE_DOCUMENT_DIFF, params, requestCallback);
   }

   public void checkForExternalEdit(
         String id,
         ServerRequestCallback<CheckForExternalEditResult> requestCallback)
   {
      sendRequest(RPC_SCOPE, CHECK_FOR_EXTERNAL_EDIT, id, requestCallback);
   }

   public void ignoreExternalEdit(String id,
                                  ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, IGNORE_EXTERNAL_EDIT, id, requestCallback);
   }

   public void closeDocument(String id,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLOSE_DOCUMENT, id, requestCallback);
   }

   public void closeAllDocuments(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLOSE_ALL_DOCUMENTS, requestCallback);
   }

   public void setSourceDocumentOnSave(String id,
                                       boolean shouldSourceOnSave,
                                       ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, JSONBoolean.getInstance(shouldSourceOnSave));
      sendRequest(RPC_SCOPE,
                  SET_SOURCE_DOCUMENT_ON_SAVE,
                  params,
                  requestCallback);
   }
   
   public void publishPdf(String id,
                          String title,
                          boolean update,
                          ServerRequestCallback<PublishPdfResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, new JSONString(title));
      params.set(2, JSONBoolean.getInstance(update));
      
      sendRequest(RPC_SCOPE, PUBLISH_PDF, params, requestCallback);
   }

   public void isTexInstalled(ServerRequestCallback<Boolean> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  IS_TEX_INSTALLED,
                  requestCallback);
   }
   
   public String getProgressUrl(String message)
   {
      String url = getApplicationURL(SOURCE_SCOPE + "/" + "progress");
      url += "?message=" + URL.encodeComponent(message, true);
      return url;
   }
   
  
   public void saveActiveDocument(String contents,
                                  boolean sweave,
                                  ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(contents));
      params.set(1, JSONBoolean.getInstance(sweave));

      sendRequest(RPC_SCOPE,
                  SAVE_ACTIVE_DOCUMENT,
                  params,
                  requestCallback);
   }

   public void modifyDocumentProperties(
         String id,
         HashMap<String, String> properties,
         ServerRequestCallback<Void> requestCallback)
   {
      JSONObject obj = new JSONObject();
      for (Map.Entry<String, String> entry : properties.entrySet())
      {
         obj.put(entry.getKey(), entry.getValue() == null 
                                 ? JSONNull.getInstance()
                                 : new JSONString(entry.getValue()));
      }

      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, obj);

      sendRequest(RPC_SCOPE, MODIFY_DOCUMENT_PROPERTIES, params, requestCallback);
   }

   public void revertDocument(String id,
                              String fileType,
                              ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, new JSONString(fileType));
      sendRequest(RPC_SCOPE, REVERT_DOCUMENT, params, requestCallback);
   }
   
   public void removeContentUrl(String contentUrl,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, REMOVE_CONTENT_URL, contentUrl, requestCallback);
   }

   public void detectFreeVars(String code,
                              ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, DETECT_FREE_VARS, code, requestCallback);
   }

   public void getHistory(
         long startIndex, // inclusive
         long endIndex,   // exclusive
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(startIndex));
      params.set(1, new JSONNumber(endIndex));
      sendRequest(RPC_SCOPE, GET_HISTORY, params, requestCallback);
   }
   
   public void getRecentHistory(
         long maxEntries,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_RECENT_HISTORY, maxEntries, requestCallback);
   }
 
   
   public void searchHistory(
         String query, 
         long maxEntries,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(query));
      params.set(1, new JSONNumber(maxEntries));
      sendRequest(RPC_SCOPE, SEARCH_HISTORY, params, requestCallback);
   }
   
   public void searchHistoryByPrefix(
         String prefix,
         long maxEntries,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(prefix));
      params.set(1, new JSONNumber(maxEntries));
      sendRequest(RPC_SCOPE, SEARCH_HISTORY_BY_PREFIX, params, requestCallback);
   }
   
   // package-visible methods for peer classes RemoteServerAuth and
   // RemoveServerEventListener

   
   EventBus getEventBus()
   {
      return eventBus_;
   }

   RpcRequest getEvents(
                  int lastEventId, 
                  ServerRequestCallback<JsArray<ClientEvent>> requestCallback,
                  RetryHandler retryHandler)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(lastEventId));
      return sendRequest(EVENTS_SCOPE, 
                         "get_events", 
                         params, 
                         requestCallback,
                         retryHandler);
   }
   
   void handleUnauthorizedError()
   {
      // disconnect
      disconnect();
      
      // fire event
      UnauthorizedEvent event = new UnauthorizedEvent();
      eventBus_.fireEvent(event);
   }
    
   private <T> void sendRequest(String scope, 
                                String method,
                                ServerRequestCallback<T> requestCallback)
   {
      sendRequest(scope, method, new JSONArray(), requestCallback);
   }

   private <T> void sendRequest(String scope, 
                                String method, 
                                boolean param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, JSONBoolean.getInstance(param));
      sendRequest(scope, method, params, requestCallback);
   }
   
   private <T> void sendRequest(String scope, 
                                String method, 
                                long param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(param));
      sendRequest(scope, method, params, requestCallback);
   }
        
   private <T> void sendRequest(String scope, 
                                String method, 
                                String param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();
     
      // pass JSONNull if the string is null
      params.set(0, param != null ? 
                     new JSONString(param) : 
                     JSONNull.getInstance());
      
      sendRequest(scope, method, params, requestCallback);
   }
   
   private <T> void sendRequest(String scope, 
                                String method, 
                                JavaScriptObject param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();

      // pass JSONNull if the object is null
      params.set(0, param != null ? new JSONObject(param) : 
                                    JSONNull.getInstance());

      sendRequest(scope, method, params, requestCallback);
   }
   
   
   private <T> void sendRequest(final String scope, 
                                final String method, 
                                final JSONArray params,
                                final ServerRequestCallback<T> requestCallback)
   {
      // retry handler (make the same call with the same params. ensure that
      // only one retry occurs by passing null as the retryHandler)
      RetryHandler retryHandler = new RetryHandler() {
        
         public void onRetry()
         {
            // retry one time (passing null as last param ensures there
            // is no retry handler installed)
            sendRequest(scope, method, params, requestCallback, null);    
         }   

         public void onError(ServerError error)
         {
            // propagate error which caused the retry to the caller
            requestCallback.onError(error);
         }
      };
      
      // submit request (retry same request up to one time)
      sendRequest(scope, method, params, requestCallback, retryHandler);
   }
    
   
   private <T> RpcRequest sendRequest(
                              String scope, 
                              String method, 
                              JSONArray params,
                              final ServerRequestCallback<T> requestCallback,
                              final RetryHandler retryHandler)
   {   
      // ensure we are listening for events. note that we do this here
      // because we are no longer so aggressive about retrying on failed
      // get_events calls. therefore, if we retry and fail a few times
      // we may need to restart event listening. 
      ensureListeningForEvents();
 
      // create request
      String rserverURL = getApplicationURL(scope) + "/" + method;
      RpcRequest rpcRequest = new RpcRequest(rserverURL, 
                                             method, 
                                             params, 
                                             null,
                                             clientId_,
                                             clientVersion_);
      
      // send the request
      rpcRequest.send(new RpcRequestCallback() {
         public void onError(RpcRequest request, RpcError error)
         {
            // ignore errors if:
            //   - we are disconnected;
            //   - no response handler; or 
            //   - handler was cancelled
            if ( disconnected_                || 
                 (requestCallback == null)    || 
                 requestCallback.cancelled() )
            {
               return;
            }
            
            // if we have a retry handler then see if we can resolve the
            // error and then retry
            if ( resolveRpcErrorAndRetry(error, retryHandler) ) 
               return ;

            // first crack goes to globally registered rpc error handlers
            if (!handleRpcErrorInternally(error))
            {
               // no global handlers processed it, send on to caller
               requestCallback.onError(new RemoteServerError(error));
            }
         }

         public void onResponseReceived(final RpcRequest request,
                                        RpcResponse response)
         {
            // ignore response if:
            //   - we are disconnected;
            //   - no response handler; or 
            //   - handler was cancelled
            if ( disconnected_                 || 
                 (requestCallback == null)     || 
                 requestCallback.cancelled() )
            {
                 return;
            }
                   
            // check for error
            if (response.getError() != null)
            {
               // ERROR: explicit error returned by server
               RpcError error = response.getError();
               
               // if we have a retry handler then see if we can resolve the
               // error and then retry
               if ( resolveRpcErrorAndRetry(error, retryHandler) ) 
                  return ;
               
               // give first crack to internal handlers, then forward to caller
               if (!handleRpcErrorInternally(error))
                  requestCallback.onError(new RemoteServerError(error));
            }
            else if (response.getAsyncHandle() != null)
            {
               serverEventListener_.registerAsyncHandle(
                     response.getAsyncHandle(),
                     request,
                     this);
            }
            // no error, process the result
            else
            {
               // no error, get the result
               T result = response.<T> getResult();
               requestCallback.onResponseReceived(result);
               
               // always ensure that the event source receives events unless 
               // the server specifically flags us that no events are likely
               // to be pending (e.g. an rpc call where no events were added
               // to the queue by the call)
               if (eventsPending(response))
                  serverEventListener_.ensureEvents();
            }
         }
      });
      
      // return the request
      return rpcRequest;
   }
     
   private boolean eventsPending(RpcResponse response)
   {
      String eventsPending = response.getField("ep");
      if (eventsPending == null)
         return true ; // default to true for json-rpc compactness
      else
         return Boolean.parseBoolean(eventsPending);
   }
   
   private boolean resolveRpcErrorAndRetry(final RpcError error,
                                           final RetryHandler retryHandler)
   {
      // won't even attempt resolve if we don't have a retryHandler
      if (retryHandler == null)
         return false;
      
      // can attempt to resolve UNAUTHORIZED by updating credentials
      if (error.getCode() == RpcError.UNAUTHORIZED)
      {
         // check credentials 
         serverAuth_.updateCredentials(new ServerRequestCallback<Integer>() {

            @Override
            public void onResponseReceived(Integer response)
            {
               // allow retry on success, otherwise handle unauthorized error
               if (response.intValue() == 
                                 RemoteServerAuth.CREDENTIALS_UPDATE_SUCCESS)
               {
                  retryHandler.onRetry();
               }
               else
               {
                  handleUnauthorizedError();
               }
            }
            
            @Override
            public void onError(ServerError serverError)
            {
               // log the auth sequence error
               Debug.logError(serverError);

               // unable to resolve unauthorized error through a 
               // credentials check -- treat as unauthorized
               handleUnauthorizedError();
            }
         });
         
         // attempting to resolve
         return true;
      }
      else
      {
         return false;
      }
   }

   private boolean handleRpcErrorInternally(RpcError error)
   { 
      if (error.getCode() == RpcError.UNAUTHORIZED)
      {
         handleUnauthorizedError();
         return true;
      }
      else if (error.getCode() == RpcError.INVALID_CLIENT_ID)
      {
         // disconnect
         disconnect();
         
         // fire event
         ClientDisconnectedEvent event = new ClientDisconnectedEvent();
         eventBus_.fireEvent(event);
         
         // handled
         return true;
      }
      else if (error.getCode() == RpcError.INVALID_CLIENT_VERSION)
      {
         // disconnect
         disconnect();
         
         // fire event
         InvalidClientVersionEvent event = new InvalidClientVersionEvent();
         eventBus_.fireEvent(event);
         
         // handled
         return true;
      }
      else if (error.getCode() == RpcError.SERVER_OFFLINE)
      {
         // disconnect
         disconnect();
         
         // fire event
         ServerOfflineEvent event = new ServerOfflineEvent();
         eventBus_.fireEvent(event);
         
         // handled
         return true;
         
      }
      else
      {
         return false;
      } 
   }
  
   private void disconnect()
   {
      disconnected_ = true;
      serverEventListener_.stop();
   }
  
   private String clientId_;
   private double clientVersion_ = 0;
   private boolean workbenchReady_;
   private boolean disconnected_;
   
   private final RemoteServerAuth serverAuth_;
   private final RemoteServerEventListener serverEventListener_ ;
  
   private final Session session_;
   private final EventBus eventBus_;
  
   // url scopes
   private static final String RPC_SCOPE = "rpc";
   private static final String FILES_SCOPE = "files";
   private static final String EVENTS_SCOPE = "events";
   private static final String HELP_SCOPE = "help";
   private static final String UPLOAD_SCOPE = "upload";
   private static final String EXPORT_SCOPE = "export";
   private static final String GRAPHICS_SCOPE = "graphics";   
   private static final String SOURCE_SCOPE = "source";
   private static final String LOG_SCOPE = "log";
   private static final String FILE_SHOW = "file_show";
   
   // session methods
   private static final String CLIENT_INIT = "client_init";
   private static final String ACCEPT_AGREEMENT = "accept_agreement";
   private static final String SUSPEND_SESSION = "suspend_session";
   private static final String QUIT_SESSION = "quit_session";
   
   private static final String SET_WORKBENCH_METRICS = "set_workbench_metrics";
   private static final String SET_UI_PREFS = "set_ui_prefs";
   private static final String GET_R_PREFS = "get_r_prefs";
   private static final String SET_R_PREFS = "set_r_prefs";
   private static final String SET_CLIENT_STATE = "set_client_state";
   private static final String USER_PROMPT_COMPLETED = "user_prompt_completed";
   
   private static final String CONSOLE_INPUT = "console_input";
   private static final String RESET_CONSOLE_ACTIONS = "reset_console_actions";
   private static final String INTERRUPT = "interrupt";
   private static final String ABORT = "abort";
   private static final String HTTP_LOG = "http_log";
   private static final String GET_COMPLETIONS = "get_completions";
   
   private static final String LIST_OBJECTS = "list_objects";
   private static final String REMOVE_ALL_OBJECTS = "remove_all_objects";
   private static final String SET_OBJECT_VALUE = "set_object_value";
   private static final String GET_OBJECT_VALUE = "get_object_value";
   private static final String SAVE_WORKSPACE = "save_workspace";
   private static final String LOAD_WORKSPACE = "load_workspace";
   private static final String LIST_GOOGLE_SPREADSHEETS = "list_google_spreadsheets";
   private static final String IMPORT_GOOGLE_SPREADSHEET = "import_google_spreadsheet";
   private static final String DOWNLOAD_DATA_FILE = "download_data_file";
   private static final String GET_DATA_PREVIEW = "get_data_preview";
   private static final String GET_OUTPUT_PREVIEW = "get_output_preview";

   private static final String EDIT_COMPLETED = "edit_completed";
   private static final String CHOOSE_FILE_COMPLETED = "choose_file_completed";
   
   private static final String LIST_PACKAGES = "list_packages";
   private static final String AVAILABLE_PACKAGES = "available_packages";
   private static final String LOAD_PACKAGE = "load_package";
   private static final String UNLOAD_PACKAGE = "unload_package";
   private static final String IS_CRAN_CONFIGURED = "is_cran_configured";
   private static final String SET_CRAN_REPOS_URL = "set_cran_repos_url";

   private static final String GET_HELP = "get_help";
   private static final String SHOW_HELP_TOPIC = "show_help_topic" ;
   private static final String SEARCH = "search" ;
   private static final String GET_HELP_LINKS = "get_help_links" ;
   private static final String SET_HELP_LINKS = "set_help_links" ;

   private static final String LIST_FILES = "list_files";
   private static final String LIST_ALL_FILES = "list_all_files";
   private static final String CREATE_FILE = "create_file";
   private static final String CREATE_FOLDER = "create_folder";
   private static final String DELETE_FILES = "delete_files";
   private static final String COPY_FILE = "copy_file";
   private static final String MOVE_FILES = "move_files";
   private static final String RENAME_FILE = "rename_file";
   private static final String COMPLETE_UPLOAD = "complete_upload";

   private static final String NEXT_PLOT = "next_plot";
   private static final String PREVIOUS_PLOT = "previous_plot";
   private static final String CLEAR_PLOTS = "clear_plots";
   private static final String REFRESH_PLOT = "refresh_plot";
   private static final String EXPORT_PLOT = "export_plot";
   private static final String LOCATOR_COMPLETED = "locator_completed";
   private static final String SET_MANIPULATOR_VALUES = "set_manipulator_values";

   private static final String NEW_DOCUMENT = "new_document";
   private static final String OPEN_DOCUMENT = "open_document";
   private static final String LIST_DOCUMENTS = "list_documents";
   private static final String SAVE_DOCUMENT = "save_document";
   private static final String SAVE_DOCUMENT_DIFF = "save_document_diff";
   private static final String CHECK_FOR_EXTERNAL_EDIT = "check_for_external_edit";
   private static final String IGNORE_EXTERNAL_EDIT = "ignore_external_edit";
   private static final String CLOSE_DOCUMENT = "close_document";
   private static final String CLOSE_ALL_DOCUMENTS = "close_all_documents";
   private static final String SET_SOURCE_DOCUMENT_ON_SAVE
         = "set_source_document_on_save";
   private static final String SAVE_ACTIVE_DOCUMENT = "save_active_document";
   private static final String MODIFY_DOCUMENT_PROPERTIES = "modify_document_properties";
   private static final String REVERT_DOCUMENT = "revert_document";
   private static final String REMOVE_CONTENT_URL = "remove_content_url";
   private static final String DETECT_FREE_VARS = "detect_free_vars";
   private static final String PUBLISH_PDF = "publish_pdf";
   private static final String IS_TEX_INSTALLED = "is_tex_installed";

   private static final String GET_HISTORY = "get_history";
   private static final String GET_RECENT_HISTORY = "get_recent_history";
   private static final String SEARCH_HISTORY = "search_history";
   private static final String SEARCH_HISTORY_BY_PREFIX = "search_history_by_prefix";

   private static final String LOG = "log";
}
