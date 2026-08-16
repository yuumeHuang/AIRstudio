/*
 * CondaEnvsResult.java
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * Response of the get_conda_r_versions session RPC: the environment list
 * plus the R home the session is actually running (for marking the
 * current environment).
 */
package org.rstudio.studio.client.application.model;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;

public class CondaEnvsResult extends JavaScriptObject
{
   protected CondaEnvsResult() {}

   public final native JsArray<CondaEnvSpec> getEnvs() /*-{ return this.envs || []; }-*/;
   public final native String getCurrentRHome() /*-{ return this.current_r_home || ""; }-*/;
   public final native String getSystemRHome() /*-{ return this.system_r_home || ""; }-*/;
}
