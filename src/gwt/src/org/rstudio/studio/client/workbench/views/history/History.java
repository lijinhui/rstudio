/*
 * History.java
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
package org.rstudio.studio.client.workbench.views.history;

import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.event.logical.shared.HasValueChangeHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Command;
import com.google.inject.Inject;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.TimeBufferedCommand;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.events.HasSelectionCommitHandlers;
import org.rstudio.core.client.events.SelectionCommitEvent;
import org.rstudio.core.client.events.SelectionCommitHandler;
import org.rstudio.core.client.jsonrpc.RpcObjectList;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.WorkbenchView;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.helper.StringStateValue;
import org.rstudio.studio.client.workbench.views.BasePresenter;
import org.rstudio.studio.client.workbench.views.console.events.SendToConsoleEvent;
import org.rstudio.studio.client.workbench.views.history.History.Display.Mode;
import org.rstudio.studio.client.workbench.views.history.events.FetchCommandsEvent;
import org.rstudio.studio.client.workbench.views.history.events.FetchCommandsHandler;
import org.rstudio.studio.client.workbench.views.history.events.HistoryEntriesAddedEvent;
import org.rstudio.studio.client.workbench.views.history.events.HistoryEntriesAddedHandler;
import org.rstudio.studio.client.workbench.views.history.model.HistoryEntry;
import org.rstudio.studio.client.workbench.views.history.model.HistoryServerOperations;
import org.rstudio.studio.client.workbench.views.source.events.InsertSourceEvent;

import java.util.ArrayList;

public class History extends BasePresenter implements SelectionCommitHandler<Void>,
                                                      FetchCommandsHandler
{
   public interface SearchBoxDisplay extends HasValueChangeHandlers<String>
   {
      String getText();
      public void setText(String text);
   }

   public interface Display extends WorkbenchView,
                                    HasSelectionCommitHandlers<Void>
   {
      public enum Mode
      {
         Recent(0),
         SearchResults(1),
         CommandContext(2);

         Mode(int value)
         {
            value_ = value;
         }

         public int getValue()
         {
            return value_;
         }

         private final int value_;
      }

      void setRecentCommands(ArrayList<HistoryEntry> commands);
      void addRecentCommands(ArrayList<HistoryEntry> entries, boolean top);
      ArrayList<String> getSelectedCommands();
      ArrayList<Long> getSelectedCommandIndexes();
      HandlerRegistration addFetchCommandsHandler(FetchCommandsHandler handler);
      void setMoreCommands(long moreCommands);
      SearchBoxDisplay getSearchBox();
      Mode getMode();
      void scrollToBottom();

      void dismissSearchResults();
      void showSearchResults(String query,
                             ArrayList<HistoryEntry> entries);
      void showContext(String command,
                       ArrayList<HistoryEntry> entries,
                       long highlightOffset,
                       long highlightLength);
      void dismissContext();

      HasHistory getRecentCommandsWidget();
      HasHistory getSearchResultsWidget();
      HasHistory getCommandContextWidget();

      boolean isCommandTableFocused();
   }

   public interface Binder extends CommandBinder<Commands, History>
   {}


   class SearchCommand extends TimeBufferedCommand implements ValueChangeHandler<String>
   {
      SearchCommand(Session session)
      {
         super(200);
      }

      @Override
      protected void performAction(boolean shouldSchedulePassive)
      {
         final String query = searchQuery_;
         if (searchQuery_ != null && searchQuery_.length() > 0)
         {
            server_.searchHistory(
                  searchQuery_, COMMAND_CHUNK_SIZE,
                  new SimpleRequestCallback<RpcObjectList<HistoryEntry>>()
                  {
                     @Override
                     public void onResponseReceived(
                           RpcObjectList<HistoryEntry> response)
                     {
                        if (!query.equals(searchQuery_))
                           return;

                        ArrayList<HistoryEntry> entries = toList(response);
                        view_.showSearchResults(query, entries);
                     }
                  });
         }
      }

      public void onValueChange(ValueChangeEvent<String> event)
      {
         String query = event.getValue();
         searchQuery_ = query;
         if (searchQuery_.equals(""))
         {
            view_.dismissSearchResults();
         }
         else
         {
            nudge();
         }
      }

      public void dismissResults()
      {
         view_.dismissSearchResults();
         searchQuery_ = null;
      }

      private String searchQuery_;
   }

   @Inject
   public History(final Display view,
                  HistoryServerOperations server,
                  final GlobalDisplay globalDisplay,
                  EventBus events,
                  final Session session,
                  Commands commands,
                  Binder binder)
   {
      super(view);
      view_ = view;
      events_ = events;
      globalDisplay_ = globalDisplay;
      searchCommand_ = new SearchCommand(session);

      binder.bind(commands, this);

      view_.addSelectionCommitHandler(this);
      view_.addFetchCommandsHandler(this);

      server_ = server;
      events_.addHandler(HistoryEntriesAddedEvent.TYPE, new HistoryEntriesAddedHandler()
      {
         public void onHistoryEntriesAdded(HistoryEntriesAddedEvent event)
         {
            view_.addRecentCommands(toList(event.getEntries()), false);
         }
      });

      view_.getSearchBox().addValueChangeHandler(searchCommand_);

      view_.getRecentCommandsWidget().getKeyTarget().addKeyDownHandler(
            new KeyHandler(commands.historySendToConsole(), null, null));
      view_.getSearchResultsWidget().getKeyTarget().addKeyDownHandler(
            new KeyHandler(commands.historySendToConsole(),
                           commands.historyDismissResults(),
                           commands.historyShowContext()));
      view_.getCommandContextWidget().getKeyTarget().addKeyDownHandler(
            new KeyHandler(commands.historySendToConsole(),
                           commands.historyDismissContext(),
                           null));

      new StringStateValue("history", "query", false,
                           session.getSessionInfo().getClientState())
      {
         @Override
         protected void onInit(String value)
         {
            if (value != null && value.length() != 0)
            {
               view_.getSearchBox().setText(value);
            }
         }

         @Override
         protected String getValue()
         {
            return view_.getSearchBox().getText();
         }
      };

      restoreHistory();
   }

   private void restoreHistory()
   {
      server_.getRecentHistory(
            COMMAND_CHUNK_SIZE,
            new ServerRequestCallback<RpcObjectList<HistoryEntry>>()
      {
         @Override
         public void onResponseReceived(RpcObjectList<HistoryEntry> response)
         {
            ArrayList<HistoryEntry> result = toList(response);
            view_.setRecentCommands(result);

            if (response.length() > 0)
               historyPosition_ = response.get(0).getIndex();
            view_.setMoreCommands(Math.min(historyPosition_, COMMAND_CHUNK_SIZE));
         }

         @Override
         public void onError(ServerError error)
         {
            globalDisplay_.showErrorMessage("Error While Retrieving History",
                                           error.getUserMessage());
         }
      });
   }

   private class KeyHandler implements KeyDownHandler
   {
      private KeyHandler(Command accept, Command left, Command right)
      {
         this.accept_ = accept;
         this.left_ = left;
         this.right_ = right;
      }

      public void onKeyDown(KeyDownEvent event)
      {
         if (event.isAnyModifierKeyDown() || !view_.isCommandTableFocused())
            return;

         boolean handled = false;
         switch (event.getNativeKeyCode())
         {
            case KeyCodes.KEY_ENTER:
               if (accept_ != null)
                  accept_.execute();
               handled = true;
               break;
            case KeyCodes.KEY_ESCAPE:
            case KeyCodes.KEY_LEFT:
               if (left_ != null)
                  left_.execute();
               handled = true;
               break;
            case KeyCodes.KEY_RIGHT:
               if (right_ != null)
                  right_.execute();
               handled = true;
               break;
         }
         if (handled)
         {
            event.preventDefault();
            event.stopPropagation();
         }
      }

      private final Command accept_;
      private final Command left_;
      private final Command right_;
   }

   @Override
   public void onSelected()
   {
      super.onSelected();
      if (view_.getMode() == Mode.Recent)
         view_.scrollToBottom();
   }

   private String getSelectedCommands()
   {
      ArrayList<String> commands = view_.getSelectedCommands();
      StringBuilder cmd = new StringBuilder();
      for (String command : commands)
      {
         cmd.append(command);
         cmd.append("\n");
      }
      String commandString = cmd.toString();
      return commandString;
   }

   @Handler
   void onHistorySendToConsole()
   {
      String commandString = getSelectedCommands();
      commandString = StringUtil.chomp(commandString);
      if (commandString.length() > 0 )
         events_.fireEvent(new SendToConsoleEvent(commandString, false));
   }

   @Handler
   void onHistorySendToSource()
   {
      String commandString = getSelectedCommands();
      if (commandString.length() > 0)
         events_.fireEvent(new InsertSourceEvent(commandString, true));
   }

   @Handler
   void onHistoryDismissResults()
   {
      searchCommand_.dismissResults();
   }

   @Handler
   void onHistoryDismissContext()
   {
      view_.dismissContext();
   }

   @Handler
   void onHistoryShowContext()
   {
      ArrayList<Long> indexes = view_.getSelectedCommandIndexes();
      if (indexes.size() != 1)
         return;

      final String command = view_.getSelectedCommands().get(0);
      final Long min = indexes.get(0);
      final long max = indexes.get(indexes.size() - 1) + 1;
      final long start = Math.max(0, min - CONTEXT_LINES);
      final long end = max + CONTEXT_LINES;

      server_.getHistory(
            start,
            end,
            new SimpleRequestCallback<RpcObjectList<HistoryEntry>>() {
               @Override
               public void onResponseReceived(RpcObjectList<HistoryEntry> response)
               {
                  ArrayList<HistoryEntry> entries = toList(response);
                  view_.showContext(command,
                                    entries,
                                    min - start,
                                    max - min);
               }
            });
   }

   private ArrayList<HistoryEntry> toList(RpcObjectList<HistoryEntry> response)
   {
      ArrayList<HistoryEntry> entries = new ArrayList<HistoryEntry>();
      for (int i = 0; i < response.length(); i++)
         entries.add(response.get(i));
      return entries;
   }

   public void onSelectionCommit(SelectionCommitEvent<Void> e)
   {
      onHistorySendToConsole();
   }

   public void onFetchCommands(FetchCommandsEvent event)
   {
      if (fetchingMoreCommands_)
         return;

      if (historyPosition_ == 0)
      {
         // This should rarely/never happen
         return;
      }

      long startIndex = Math.max(0, historyPosition_ - COMMAND_CHUNK_SIZE);
      long endIndex = historyPosition_;
      server_.getHistory(startIndex, endIndex,
            new SimpleRequestCallback<RpcObjectList<HistoryEntry>>()
            {
               @Override
               public void onResponseReceived(RpcObjectList<HistoryEntry> response)
               {
                  ArrayList<HistoryEntry> entries = toList(response);
                  view_.addRecentCommands(entries, true);
                  fetchingMoreCommands_ = false;

                  if (response.length() > 0)
                     historyPosition_ = response.get(0).getIndex();
                  else
                     historyPosition_ = 0; // this shouldn't happen

                  view_.setMoreCommands(Math.min(historyPosition_,
                                                 COMMAND_CHUNK_SIZE));
               }

               @Override
               public void onError(ServerError error)
               {
                  super.onError(error);
                  fetchingMoreCommands_ = false;
               }
            });
   }

   // This field indicates how far into the history stream we have reached.
   // When this value becomes 0, that means there is no more history to go
   // fetch.
   private long historyPosition_ = 0;

   private static final int COMMAND_CHUNK_SIZE = 300;
   private static final int CONTEXT_LINES = 50;
   private boolean fetchingMoreCommands_ = false;
   private final Display view_;
   private final EventBus events_;
   private final GlobalDisplay globalDisplay_;
   private final SearchCommand searchCommand_;
   private HistoryServerOperations server_;
}
