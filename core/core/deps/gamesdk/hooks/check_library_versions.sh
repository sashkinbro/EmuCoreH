#!/bin/bash

SWAPPY_COMMON_H="include/swappy/swappy_common.h"
TUNINGFORK_H="include/tuningfork/tuningfork.h"

VERSIONS="VERSIONS"

library_names=(games-frame-pacing games-performance-tuner game-activity game-text-input games-controller games-memory-advice)
header_files=(include/swappy/swappy_common.h include/tuningfork/tuningfork.h game-activity/prefab-src/modules/game-activity/include/game-activity/GameActivity.h game-text-input/prefab-src/modules/game-text-input/include/game-text-input/gametextinput.h include/paddleboat/paddleboat.h include/memory_advice/memory_advice.h)
library_version_prefix=(SWAPPY TUNINGFORK GAMEACTIVITY GAMETEXTINPUT PADDLEBOAT MEMORY_ADVICE)


for i in "${!library_names[@]}"; do
  library="${library_names[i]}"
  header="${header_files[i]}"
  prefix="${library_version_prefix[i]}"
  [[ `cat VERSIONS` =~ $library[[:space:]]*([0-9]+.[0-9]+.[0-9]+)[[:space:]]*([a-z]*) ]]
  versions_version="${BASH_REMATCH[1]}"
  version_suffix="${BASH_REMATCH[2]}"
  [[ `cat $header` =~ ${prefix}_MAJOR_VERSION.([0-9]+) ]]
  major="${BASH_REMATCH[1]}"
  [[ `cat $header` =~ ${prefix}_MINOR_VERSION.([0-9]+) ]]
  minor="${BASH_REMATCH[1]}"
  [[ `cat $header` =~ ${prefix}_BUGFIX_VERSION.([0-9]+) ]]
  bugfix="${BASH_REMATCH[1]}"
  header_version="${major}.${minor}.${bugfix}"
  if [[ "${major}.${minor}.${bugfix}" != $versions_version ]]; then
    echo "Version mismatch! For ${library}, the version declared in its header is ${header_version} but the version declared in the VERSIONS file is ${versions_version}."
    exit 1
  fi
  if [[ $version_suffix =~ "alpha" ]] && [[ $bugfix != "0" ]]; then
    echo "Invalid version! ${library} is declared as an alpha release, but has a bugfix version."
    exit 1
  fi
  if [[ $version_suffix =~ "beta" ]] && [[ $bugfix != "0" ]]; then
    echo "Invalid version! ${library} is declared as a beta release, but has a bugfix version."
    exit 1
  fi
done

exit 0
