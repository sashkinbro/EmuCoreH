#!/bin/bash
STRING=\"$(sed 's/"/\\"/g' script.json)\"

echo $STRING
function runIt() {
  adb logcat -c
  sleep 1
  adb shell am start -n com.prefabulated.swappy/com.prefabulated.bouncyball.OrbitActivity -e TEST_SCRIPT $STRING
  sleep 1
  adb logcat > out.log
}
function stopIt() {
  sleep 60
  adb shell am force-stop com.prefabulated.swappy
}
runIt & stopIt
list_descendants ()
{
  local children=$(ps -o pid= --ppid "$1")

  for pid in $children
  do
    list_descendants "$pid"
  done

  echo "$children"
}

kill $(list_descendants $$)
grep FrameStat out.log > logcat_step.txt
python3 analyse.py
