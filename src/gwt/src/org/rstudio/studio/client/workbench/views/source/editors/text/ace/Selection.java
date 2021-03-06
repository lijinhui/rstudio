/*
 * Selection.java
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
package org.rstudio.studio.client.workbench.views.source.editors.text.ace;

import com.google.gwt.core.client.JavaScriptObject;

public class Selection extends JavaScriptObject
{
   protected Selection() {}

   public native final Range getRange() /*-{
      return this.getRange();
   }-*/;

   public native final void setSelectionRange(Range range) /*-{
      this.setSelectionRange(range);
   }-*/;

   public native final Position getCursor() /*-{
      return this.getCursor();
   }-*/;

   public native final void moveCursorTo(int row,
                                         int column,
                                         boolean preventUpdateDesiredColumn) /*-{
      this.moveCursorTo(row, column, preventUpdateDesiredColumn);
   }-*/;

   public native final boolean isEmpty() /*-{
      return this.isEmpty();
   }-*/;

   public native final void selectAll() /*-{
      this.selectAll();
   }-*/;
}
