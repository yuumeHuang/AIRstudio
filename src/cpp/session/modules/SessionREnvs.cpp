/*
 * SessionREnvs.cpp
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * Serves the conda R environment list for the toolbar environment switcher.
 * Entries come from /etc/air-studio/r-versions, which the
 * air-studio-conda-r-versions generator refreshes from public conda roots and
 * every user's personal env roots. Since this process runs as the session
 * user, entries under another user's home are filtered out: each user sees
 * public environments plus their own personal ones only.
 */
#include "SessionREnvs.hpp"

#include <boost/algorithm/string.hpp>

#include <string>
#include <vector>

#include <core/Exec.hpp>
#include <core/FileSerializer.hpp>
#include <core/json/JsonRpc.hpp>
#include <core/system/System.hpp>
#include <core/system/Xdg.hpp>
#include <session/RVersionSettings.hpp>
#include <session/SessionConstants.hpp>
#include <session/SessionModuleContext.hpp>
#include <session/SessionOptions.hpp>

using namespace rstudio::core;

namespace rstudio {
namespace session {
namespace modules {
namespace r_envs {

namespace {

struct EnvEntry
{
   std::string label;
   std::string path;
   std::string version;
};

// Parse the DCF entries written by the generator: blocks separated by blank
// lines, each containing Label:/Path:/Version: lines.
std::vector<EnvEntry> parseEnvEntries(const std::string& contents)
{
   std::vector<EnvEntry> entries;
   std::string label, path, version;
   bool haveEntry = false;

   auto flush = [&]()
   {
      if (haveEntry && !path.empty())
         entries.push_back({label, path, version});
      label.clear();
      path.clear();
      version.clear();
      haveEntry = false;
   };

   std::istringstream stream(contents);
   std::string line;
   while (std::getline(stream, line))
   {
      // tolerate CRLF
      if (!line.empty() && line.back() == '\r')
         line.pop_back();

      if (line.empty())
      {
         flush();
         continue;
      }
      if (line[0] == '#')
         continue;

      auto colon = line.find(':');
      if (colon == std::string::npos)
         continue;
      std::string key = line.substr(0, colon);
      std::string value = line.substr(colon + 1);
      boost::algorithm::trim(value);

      if (key == "Label")
      {
         flush(); // be safe: start a new entry on every Label line
         label = value;
         haveEntry = true;
      }
      else if (key == "Path")
      {
         path = value;
         haveEntry = true;
      }
      else if (key == "Version")
      {
         version = value;
         haveEntry = true;
      }
   }
   flush();
   return entries;
}

bool startsWith(const std::string& str, const std::string& prefix)
{
   return str.size() >= prefix.size() && str.compare(0, prefix.size(), prefix) == 0;
}

Error getCondaRVersions(const json::JsonRpcRequest& request,
                        json::JsonRpcResponse* pResponse)
{
   json::Array result;

   FilePath versionsFile = core::system::xdg::findSystemConfigFile(
         "conda R environments", "r-versions");
   if (versionsFile.exists())
   {
      std::string contents;
      Error error = readStringFromFile(versionsFile, &contents,
                                       string_utils::LineEndingPosix);
      if (error)
      {
         LOG_ERROR(error);
      }
      else
      {
         std::string homeDir = core::system::userHomePath().getAbsolutePath();
         for (const EnvEntry& entry : parseEnvEntries(contents))
         {
            // personal environments of other users are not accessible to
            // this session user; show public envs plus the user's own
            if (startsWith(entry.path, "/home/") && !startsWith(entry.path, homeDir + "/"))
               continue;

            json::Object env;
            env["label"] = entry.label;
            env["path"] = entry.path;
            env["version"] = entry.version;
            env["r_home"] = entry.path + "/lib/R";
            env["personal"] = startsWith(entry.path, homeDir + "/");
            result.push_back(std::move(env));
         }
      }
   }

   json::Object response;
   response["envs"] = result;
   response["current_r_home"] = module_context::rHomeDir();
   // the launcher wrapper exports the rserver-provided R home before
   // overriding it, so the toolbar can offer switching back to system R
   std::string systemRHome = core::system::getenv("RSTUDIO_SYSTEM_R_HOME");
   if (systemRHome.empty())
      systemRHome = core::system::getenv("R_HOME");
   response["system_r_home"] = systemRHome;
   pResponse->setResult(response);
   return Success();
}

Error switchREnvironment(const json::JsonRpcRequest& request,
                         json::JsonRpcResponse* pResponse)
{
   std::string rHome, version, label;
   Error error = json::readParams(request.params, &rHome, &version, &label);
   if (error)
      return error;

   FilePath rHomeDir(rHome);
   if (rHome.empty() || !rHomeDir.isDirectory() ||
       !rHomeDir.completeChildPath("lib/libR.so").exists())
   {
      return Error(json::errc::ParamInvalid, ERROR_LOCATION);
   }

   // persist the choice on the active session: the rsession launcher wrapper
   // (configured via rserver.conf rsession-path) reads
   // properites/r-version-home and starts rsession with this R
   module_context::activeSession().setRVersion(version, rHome, label);

   // keep the user-level default in sync so brand-new sessions start in the
   // chosen environment as well
   RVersionSettings versionSettings(module_context::userScratchPath(),
                                    FilePath(options().getOverlayOption(
                                                kSessionSharedStoragePath)));
   versionSettings.setDefaultRVersion(version, rHome, label);

   pResponse->setResult(true);
   return Success();
}

} // anonymous namespace

core::Error initialize()
{
   using namespace module_context;

   ExecBlock initBlock;
   initBlock.addFunctions()
         (bind(registerRpcMethod, "get_conda_r_versions", getCondaRVersions))
         (bind(registerRpcMethod, "switch_r_environment", switchREnvironment));
   return initBlock.execute();
}

} // namespace r_envs
} // namespace modules
} // namespace session
} // namespace rstudio
