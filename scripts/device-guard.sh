# Sourced by the scripts that write to a device. Refuses any device listed in AMP_PROTECTED_SERIALS (separated by
# spaces), matching the hardware serial (ro.serialno) as well as the adb serial, because a wireless adb serial is
# either the mdns name or a bare ip:port. With AMP_PROTECTED_SERIALS unset, every device is allowed.
# Usage: guard_device <serial> <what the caller is about to do>
guard_device() {
  local serial="$1" what="$2" hw protected
  local -a list
  read -r -a list <<<"${AMP_PROTECTED_SERIALS:-}"
  if [[ ${#list[@]} -eq 0 ]]; then
    return 0
  fi
  protected=" ${list[*]} "
  if [[ "$protected" == *" $serial "* ]]; then
    echo "refusing to $what on protected device $serial" >&2
    exit 2
  fi
  hw="$(adb -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r')" || true
  if [[ -z "$hw" ]]; then
    echo "refusing to $what on $serial: cannot read its hardware serial to check AMP_PROTECTED_SERIALS" >&2
    exit 2
  fi
  if [[ "$protected" == *" $hw "* ]]; then
    echo "refusing to $what on protected device $serial ($hw)" >&2
    exit 2
  fi
}
