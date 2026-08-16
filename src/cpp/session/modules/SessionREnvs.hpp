/*
 * SessionREnvs.hpp
 *
 * Copyright (C) 2026 by bioagent contributors
 *
 * Exposes conda R environments (public + the current user's personal ones,
 * as enumerated in /etc/rstudio/r-versions by the rstudio-conda-r-versions
 * generator) to the workbench toolbar environment switcher.
 */
#ifndef SESSION_MODULE_R_ENVS_HPP
#define SESSION_MODULE_R_ENVS_HPP

#include <shared_core/Error.hpp>

namespace rstudio {
namespace session {
namespace modules {
namespace r_envs {

core::Error initialize();

} // namespace r_envs
} // namespace modules
} // namespace session
} // namespace rstudio

#endif // SESSION_MODULE_R_ENVS_HPP
