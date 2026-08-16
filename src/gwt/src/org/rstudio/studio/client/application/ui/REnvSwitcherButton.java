/*
 * REnvSwitcherButton.java
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * Toolbar dropdown listing the conda R environments available to the
 * current user (public environments plus their own personal ones, served
 * by the get_conda_r_versions session RPC). Selecting an environment
 * restarts the R session running that environment's R, exactly like the
 * stock "Change R version" flow.
 */
package org.rstudio.studio.client.application.ui;

import com.google.gwt.core.client.JsArray;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.MenuItem;

import org.rstudio.core.client.resources.CoreResources;
import org.rstudio.core.client.widget.ScrollableToolbarPopupMenu;
import org.rstudio.core.client.widget.ToolbarButton;
import org.rstudio.core.client.widget.ToolbarMenuButton;
import org.rstudio.core.client.widget.ToolbarPopupMenu;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.ApplicationQuit;
import org.rstudio.studio.client.application.model.ApplicationServerOperations;
import org.rstudio.studio.client.application.model.CondaEnvSpec;
import org.rstudio.studio.client.application.model.CondaEnvsResult;
import org.rstudio.studio.client.application.model.RVersionSpec;
import org.rstudio.studio.client.application.model.RVersionsInfo;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.prefs.model.UserPrefs;

import com.google.inject.Inject;

public class REnvSwitcherButton extends ToolbarMenuButton
{
   public REnvSwitcherButton()
   {
      super("R",
            ToolbarButton.NoTitle,
            CoreResources.INSTANCE.iconEmpty(),
            new ScrollableToolbarPopupMenu()
            {
               @Override
               protected int getMaxHeight()
               {
                  return 400;
               }
            },
            false);
      RStudioGinjector.INSTANCE.injectMembers(this);
      getElement().setId("rstudio_renv_switcher");
      menu_ = getMenu();
      addClickHandler(event -> populate());
   }

   @Inject
   public void initialize(ApplicationServerOperations server,
                          GlobalDisplay globalDisplay,
                          Session session,
                          ApplicationQuit applicationQuit,
                          UserPrefs userPrefs)
   {
      server_ = server;
      globalDisplay_ = globalDisplay;
      session_ = session;
      applicationQuit_ = applicationQuit;
      userPrefs_ = userPrefs;
   }

   private void populate()
   {
      menu_.clearItems();
      menu_.addItem(new MenuItem(SafeHtmlUtils.fromTrustedString(infoHtml("Loading environments…")), () -> {}));
      server_.getCondaRVersions(new ServerRequestCallback<CondaEnvsResult>()
      {
         @Override
         public void onResponseReceived(CondaEnvsResult result)
         {
            populateMenu(result);
         }

         @Override
         public void onError(ServerError error)
         {
            menu_.clearItems();
            menu_.addItem(new MenuItem(SafeHtmlUtils.fromTrustedString(
                  infoHtml("Could not load environments: " + escape(error.getUserMessage()))),
                  () -> {}));
         }
      });
   }

   private void populateMenu(CondaEnvsResult result)
   {
      menu_.clearItems();
      JsArray<CondaEnvSpec> envs = result.getEnvs();
      String currentHome = result.getCurrentRHome();

      JsArray<CondaEnvSpec> publicEnvs = filter(envs, false);
      JsArray<CondaEnvSpec> personalEnvs = filter(envs, true);

      if (publicEnvs.length() == 0 && personalEnvs.length() == 0)
      {
         menu_.addItem(new MenuItem(SafeHtmlUtils.fromTrustedString(infoHtml("No conda R environments found")), () -> {}));
         return;
      }

      // always offer the system R so users can switch back from an env
      String systemHome = result.getSystemRHome();
      if (systemHome != null && systemHome.length() > 0)
      {
         boolean current = systemHome.equals(currentHome);
         StringBuilder html = new StringBuilder();
         if (current)
            html.append("<span class=\"renv-check\">✓</span> ");
         html.append(escape("System default"));
         final String home = systemHome;
         menu_.addItem(new MenuItem(SafeHtmlUtils.fromTrustedString(html.toString()),
               () -> confirmSwitch(home, "", "System default")));
         menu_.addSeparator();
      }

      if (publicEnvs.length() > 0)
      {
         addSection("Public environments");
         for (int i = 0; i < publicEnvs.length(); i++)
            addEnvItem(publicEnvs.get(i), currentHome);
      }
      if (personalEnvs.length() > 0)
      {
         if (publicEnvs.length() > 0)
            menu_.addSeparator();
         addSection("My environments");
         for (int i = 0; i < personalEnvs.length(); i++)
            addEnvItem(personalEnvs.get(i), currentHome);
      }
   }

   private void addSection(String title)
   {
      MenuItem header = new MenuItem(SafeHtmlUtils.fromTrustedString(headerHtml(title)), () -> {});
      header.setEnabled(false);
      menu_.addItem(header);
   }

   private void addEnvItem(final CondaEnvSpec env, String currentHome)
   {
      String name = displayName(env.getLabel());
      boolean current = currentHome != null && currentHome.equals(env.getRHome());

      StringBuilder html = new StringBuilder();
      if (current)
         html.append("<span class=\"renv-check\">\u2713</span> ");
      html.append(escape(name));
      if (!env.getVersion().isEmpty())
         html.append(" <span class=\"renv-version\">R ").append(escape(env.getVersion())).append("</span>");

      menu_.addItem(new MenuItem(SafeHtmlUtils.fromTrustedString(html.toString()),
            () -> confirmSwitch(env.getRHome(), env.getVersion(), displayName(env.getLabel()))));
   }

   private void confirmSwitch(final String rHome, final String version, final String name)
   {
      globalDisplay_.showYesNoMessage(
            GlobalDisplay.MSG_WARNING,
            "Switch R Environment",
            "Restart your R session using the environment '" + name + "'? " +
            "The workspace will be saved and restored.",
            () -> switchTo(rHome, version, name),
            false);
   }

   private void switchTo(final String rHome, final String version, final String name)
   {
      server_.switchREnvironment(rHome, version, name, new ServerRequestCallback<Boolean>()
      {
         @Override
         public void onResponseReceived(Boolean response)
         {
            // the rsession launcher wrapper picks up the persisted choice
            // and restarts the session with the new R
            RStudioGinjector.INSTANCE.getCommands().restartR().execute();
         }

         @Override
         public void onError(ServerError error)
         {
            globalDisplay_.showErrorMessage(
                  "Switch R Environment",
                  "Could not switch to '" + name + "': " + error.getUserMessage());
         }
      });
   }

   private String currentRHome()
   {
      RVersionsInfo info = session_.getSessionInfo().getRVersionsInfo();
      return info == null ? null : info.getRVersionHome();
   }

   private static String infoHtml(String text)
   {
      return "<span class=\"renv-menu-info\">" + escape(text) + "</span>";
   }

   private static String headerHtml(String title)
   {
      return "<span class=\"renv-menu-header\">" + escape(title) + "</span>";
   }

   private static JsArray<CondaEnvSpec> filter(JsArray<CondaEnvSpec> envs, boolean personal)
   {
      JsArray<CondaEnvSpec> result = JsArray.createArray().cast();
      for (int i = 0; i < envs.length(); i++)
         if (envs.get(i).isPersonal() == personal)
            result.push(envs.get(i));
      return result;
   }

   // "conda: sc-r-base (public)" -> "sc-r-base"
   private static String displayName(String label)
   {
      String name = label;
      if (name.startsWith("conda: "))
         name = name.substring("conda: ".length());
      int cut = name.indexOf(" (");
      if (cut > 0)
         name = name.substring(0, cut);
      return name;
   }

   private static String escape(String text)
   {
      return SafeHtmlUtils.htmlEscape(text == null ? "" : text);
   }

   private ApplicationServerOperations server_;
   private GlobalDisplay globalDisplay_;
   private Session session_;
   private ApplicationQuit applicationQuit_;
   private UserPrefs userPrefs_;
   private final ToolbarPopupMenu menu_;
}
