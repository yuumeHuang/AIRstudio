/*
 * ChatBridge.java
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * Bridges the RStudio console (agent mode) to the bioagent chat backend:
 * obtains the backend WebSocket URL from rsession and relays user messages
 * and agent events over it. Agent prose replies are surfaced to the Shell
 * for inline rendering in the console.
 */
package org.rstudio.studio.client.workbench.views.console.shell;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Timer;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.views.chat.server.ChatServerOperations;

public class ChatBridge
{
   public interface AgentMessageDisplay
   {
      void onAgentProse(String text, boolean done);
   }

   private interface ConnectedCallback
   {
      void onConnected();
      void onFailure(String error);
   }

   public ChatBridge(AgentMessageDisplay display, ChatServerOperations chatServer)
   {
      display_ = display;
      chatServer_ = chatServer;
   }

   public void send(String text)
   {
      ensureConnected(new ConnectedCallback()
      {
         @Override
         public void onConnected()
         {
            sendRaw(text);
         }

         @Override
         public void onFailure(String error)
         {
            display_.onAgentProse("[bioagent unavailable: " + error + "]\n", true);
         }
      });
   }

   private void ensureConnected(final ConnectedCallback cb)
   {
      if (isSocketOpen())
      {
         cb.onConnected();
         return;
      }

      chatServer_.chatGetBackendUrl(new ServerRequestCallback<JsObject>()
      {
         @Override
         public void onResponseReceived(JsObject response)
         {
            BackendUrl url = response.cast();
            if (url == null || StringUtil.isNullOrEmpty(url.getUrl()) || !url.getReady())
            {
               cb.onFailure("backend not ready");
               return;
            }
            connect(url.getUrl());
            new Timer()
            {
               @Override
               public void run()
               {
                  if (isSocketOpen())
                     cb.onConnected();
                  else
                     cb.onFailure("connect timeout");
               }
            }.schedule(3000);
         }

         @Override
         public void onError(ServerError error)
         {
            cb.onFailure(error.getUserMessage());
         }
      });
   }

   private native void connect(String url) /*-{
      var self = this;
      var abs = url.indexOf("ws") === 0 ? url
         : ($wnd.location.protocol === "https:" ? "wss://" : "ws://") + $wnd.location.host + url;
      // The posit-assistant-auth cookie is (re)set only when ai-chat static
      // files are served, and the backend rotates its token on every restart,
      // so refresh the cookie before opening the socket. Opening from the
      // fetch callback guarantees the fresh cookie is in place.
      var open = function() {
         var sock = new WebSocket(abs);
         self.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::sock = sock;
         sock.onopen = function() {
            self.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::onOpen()();
         };
         sock.onclose = function() {
            self.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::onClose()();
         };
         sock.onmessage = function(ev) {
            self.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::onMessage(Ljava/lang/String;)(ev.data);
         };
      };
      // Static ai-chat assets are served by rsession at the session-relative
      // path (not the portmapped WS path), and serving them re-sets the
      // auth cookie. Fetch that, then open the socket.
      $wnd.fetch("ai-chat/index.html?_t=" + Date.now(), { credentials: "include" })
         .then(open, open);
   }-*/;

   private native boolean isSocketOpen() /*-{
      var s = this.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::sock;
      return s != null && s.readyState === 1;
   }-*/;

   private native void sendRaw(String text) /*-{
      this.@org.rstudio.studio.client.workbench.views.console.shell.ChatBridge::sock.send(
         JSON.stringify({ type: "user_message", text: text }));
   }-*/;

   private void onOpen()
   {
      Debug.log("bioagent: chat bridge connected");
   }

   private void onClose()
   {
      Debug.log("bioagent: chat bridge closed");
   }

   private void onMessage(String data)
   {
      try
      {
         JSONValue root = JSONParser.parseStrict(data);
         if (root == null || root.isObject() == null)
            return;
         String type = str(root, "type");

         if ("error".equals(type))
         {
            display_.onAgentProse("[bioagent error: " + str(root, "message") + "]\n", true);
            return;
         }
         if (!"agent_event".equals(type))
            return;

         JSONValue ev = root.isObject().get("event");
         if (ev == null || ev.isObject() == null)
            return;
         if (!"message_end".equals(str(ev, "type")))
            return;

         JSONValue message = ev.isObject().get("message");
         if (message == null || message.isObject() == null)
            return;
         if (!"assistant".equals(str(message, "role")))
            return;

         JSONValue content = message.isObject().get("content");
         if (content == null || content.isArray() == null)
            return;

         StringBuilder prose = new StringBuilder();
         for (int i = 0; i < content.isArray().size(); i++)
         {
            JSONValue item = content.isArray().get(i);
            if (item == null || item.isObject() == null)
               continue;
            if ("text".equals(str(item, "type")))
            {
               JSONValue txt = item.isObject().get("text");
               if (txt != null && txt.isString() != null)
                  prose.append(txt.isString().stringValue());
            }
         }
         if (prose.length() > 0)
            display_.onAgentProse(prose.append("\n").toString(), true);
      }
      catch (Exception e)
      {
         Debug.log(StringUtil.notNull(e.getMessage()));
      }
   }

   private static String str(JSONValue obj, String key)
   {
      JSONValue v = obj.isObject().get(key);
      return v != null && v.isString() != null ? v.isString().stringValue() : "";
   }

   // Overlay for the chat_get_backend_url response
   static final class BackendUrl extends JavaScriptObject
   {
      protected BackendUrl() {}
      public final native String getUrl() /*-{ return this.url || ""; }-*/;
      public final native boolean getReady() /*-{ return !!this.ready; }-*/;
   }

   private final AgentMessageDisplay display_;
   private final ChatServerOperations chatServer_;
   private JavaScriptObject sock;
}
