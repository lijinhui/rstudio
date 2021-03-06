/*
 * ChooseFile.java
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
package org.rstudio.studio.client.workbench.views.choosefile;

import com.google.inject.Inject;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.widget.ProgressIndicator;
import org.rstudio.core.client.widget.ProgressOperationWithInput;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.FileDialogs;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.model.RemoteFileSystemContext;
import org.rstudio.studio.client.workbench.views.choosefile.events.ChooseFileEvent;
import org.rstudio.studio.client.workbench.views.choosefile.events.ChooseFileHandler;
import org.rstudio.studio.client.workbench.views.choosefile.model.ChooseFileServerOperations;

public class ChooseFile implements ChooseFileHandler
{
   @Inject
   public ChooseFile(EventBus events,
                     ChooseFileServerOperations server,
                     RemoteFileSystemContext fsContext,
                     FileDialogs fileDialogs)
   {
      server_ = server;
      fsContext_ = fsContext;
      fileDialogs_ = fileDialogs;

      events.addHandler(ChooseFileEvent.TYPE, this);
      
   }
   
   public void onChooseFile(ChooseFileEvent event)
   {
      fileDialogs_.openFile(
            "Choose File",
            fsContext_,
            null,
            new ProgressOperationWithInput<FileSystemItem>()
            {
               public void execute(FileSystemItem input,
                                   ProgressIndicator progress)
               {
                  String message, path;
                  if (input != null)
                  {
                     message = "Saving...";
                     path = input.getPath();
                  }
                  else
                  {
                     message = "Cancelling...";
                     path = null;
                  }
                  
                  progress.onProgress(message);
                  server_.chooseFileCompleted(
                        path,
                        new VoidServerRequestCallback(
                              progress));
               }
            });
   }
   
   private final ChooseFileServerOperations server_;
   private final RemoteFileSystemContext fsContext_;
   private final FileDialogs fileDialogs_;
}
