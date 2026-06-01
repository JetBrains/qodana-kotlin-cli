#!/usr/bin/env bash
export JAVA_HOME="/c/Users/Anna.Zhukova/work/qd-14840/jdks/labsjdk-ce-25.0.2-jvmci-b01"
export PYTHON="C:\Users\Anna.Zhukova\AppData\Roaming\uv\python\cpython-3.12-windows-x86_64-none\python.exe"
export MX_PYTHON="$PYTHON"
export PYTHONIOENCODING=utf-8
export MX_CHECK_IOENCODING=0
export GRAALVM_BOOT="$HOME/graalvm-jdk-25.0.3+9.1"
export JVMCI_VERSION_CHECK=ignore

VCVARS_OUT=$(cmd //c "$(cygpath -w "$HOME/work/qd-14840/dump-vcvars.bat")" 2>/dev/null | tr -d '\r')
in_env=0
while IFS= read -r line; do
  if [ "$in_env" = 0 ]; then
    [ "$line" = "MARKER_VCVARS_DONE_NOTHING_FOLLOWS_BEFORE_SET" ] && in_env=1
    continue
  fi
  case "$line" in
    *=*)
      k="${line%%=*}"
      v="${line#*=}"
      # Only export valid POSIX identifier names
      [[ "$k" =~ ^[A-Za-z_][A-Za-z_0-9]*$ ]] || continue
      if [ "$k" = "PATH" ]; then
        export PATH="$(cygpath --path -u "$v")"
      else
        export "$k=$v"
      fi
      ;;
  esac
done <<< "$VCVARS_OUT"

export PATH="/c/Users/Anna.Zhukova/AppData/Roaming/uv/python/cpython-3.12-windows-x86_64-none:$HOME/work/qd-14840/mx:$JAVA_HOME/bin:$PATH"
