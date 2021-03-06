/*
 * HorizontalCenterPanel.java
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

package org.rstudio.core.client.widget;

import com.google.gwt.user.client.ui.DockPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;

public class HorizontalCenterPanel extends DockPanel
{
   public HorizontalCenterPanel(Widget widget, int verticalOffset)
   {
      VerticalPanel verticalPadWidget = new VerticalPanel();
      add(verticalPadWidget, DockPanel.NORTH);
      setCellHeight(verticalPadWidget, verticalOffset + "px");
      add(widget, DockPanel.CENTER);
      setCellHorizontalAlignment(widget, DockPanel.ALIGN_CENTER);   
   }
}
