/*
 * CondaEnvSpec.java
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * One conda R environment entry as served by the get_conda_r_versions
 * session RPC (see src/cpp/session/modules/SessionREnvs.cpp).
 */
package org.rstudio.studio.client.application.model;

import com.google.gwt.core.client.JavaScriptObject;

public class CondaEnvSpec extends JavaScriptObject
{
   protected CondaEnvSpec() {}

   public final native String getLabel() /*-{ return this.label; }-*/;
   public final native String getPath() /*-{ return this.path; }-*/;
   public final native String getVersion() /*-{ return this.version || ""; }-*/;
   public final native String getRHome() /*-{ return this.r_home; }-*/;
   public final native boolean isPersonal() /*-{ return !!this.personal; }-*/;
}
