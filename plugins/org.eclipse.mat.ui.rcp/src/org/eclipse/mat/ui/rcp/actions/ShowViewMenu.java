/*******************************************************************************
 * Copyright (c) 2008 SAP AG.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.mat.ui.rcp.actions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.mat.ui.internal.Perspective;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.activities.WorkbenchActivityHelper;
import org.eclipse.ui.views.IViewDescriptor;
import org.eclipse.ui.views.IViewRegistry;

public class ShowViewMenu extends ContributionItem
{
    private IWorkbenchWindow window;
    protected boolean dirty = true;

    private Map<String, IAction> actions = new HashMap<String, IAction>(10);

    private IMenuListener menuListener = new IMenuListener()
    {
        public void menuAboutToShow(IMenuManager manager)
        {
            manager.markDirty();
            dirty = true;
        }
    };

    public ShowViewMenu(IWorkbenchWindow window)
    {
        this.window = window;
    }

    public void fill(Menu menu, int index)
    {
        if (getParent() instanceof MenuManager)
        {
            ((MenuManager) getParent()).addMenuListener(menuListener);
        }

        if (!dirty) { return; }

        MenuManager manager = new MenuManager();
        fillMenu(manager);
        IContributionItem items[] = manager.getItems();
        if (items.length == 0)
        {
            MenuItem item = new MenuItem(menu, SWT.NONE, index++);
            item.setText("No applicable view");
            item.setEnabled(false);
        }
        else
        {
            for (int i = 0; i < items.length; i++)
            {
                items[i].fill(menu, index++);
            }
        }
        dirty = false;
    }

    private void fillMenu(IMenuManager innerMgr)
    {
        // Remove all.
        innerMgr.removeAll();

        List<IAction> actions = new ArrayList<IAction>(Perspective.Views.values().length);
        
        // adding all views out of Perspective.Views to the menu
        for (Perspective.Views view : Perspective.Views.values())
        {
            IAction action = getAction(view.getId());
            if (action != null)
            {
                actions.add(action);
            }
        }

        for (IAction action : actions)
        {
            innerMgr.add(action);
        }
    }

    /**
     * Returns the action for the given view id, or null if not found.
     */
    private IAction getAction(String id)
    {
        // Keep a cache, rather than creating a new action each time,
        // so that image caching in ActionContributionItem works.
        IAction action = (IAction) actions.get(id);
        if (action == null)
        {
            IViewRegistry reg = PlatformUI.getWorkbench().getViewRegistry();
            IViewDescriptor desc = reg.find(id);
            if (desc != null)
            {
                action = new ShowViewAction(window, desc);
                action.setActionDefinitionId(id);
                actions.put(id, action);
            }
        }
        return action;
    }

    public boolean isDirty()
    {
        return dirty;
    }

    /**
     * Overridden to always return true and force dynamic menu building.
     */
    public boolean isDynamic()
    {
        return true;
    }

}
