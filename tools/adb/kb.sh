# Helpers for driving the keyboard on MuMu over adb (900x1600 screen, 2.4 px/pt). Usage: source tools/adb/kb.sh
export MSYS_NO_PATHCONV=1
D="-s 127.0.0.1:16384"
SHOTS="${SHOTS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/build/shots}"; mkdir -p "$SHOTS"
S=2.4
TOP=1082   # top of the keys in screen px (1600 - 216 keys * 2.4)

px() { python -c "print(int($1*$S))"; }
py() { python -c "print(int($TOP+$1*$S))"; }
R1=$(py 30); R2=$(py 84); R3=$(py 138); R4=$(py 192)
r1() { python -c "print(int((18.75+$1*37.5)*$S))"; }
r2() { python -c "print(int((37.25+$1*37.5)*$S))"; }
r3() { python -c "print(int((74.75+$1*37.5)*$S))"; }
tap() { adb $D shell input tap "$1" "$2"; }
hold() { adb $D shell input swipe "$1" "$2" "$3" "$4" "$5"; }   # x1 y1 x2 y2 ms
shot() { adb $D exec-out screencap -p > "$SHOTS/$1.png"; }

# Letter positions for typing lowercase words.
letter() {
  local rows=("qwertyuiop" "asdfghjkl" "zxcvbnm") c="$1" i
  for r in 0 1 2; do
    i=$(expr index "${rows[$r]}" "$c")
    if [ "$i" -gt 0 ]; then
      i=$((i-1))
      case $r in 0) tap "$(r1 $i)" "$R1";; 1) tap "$(r2 $i)" "$R2";; 2) tap "$(r3 $i)" "$R3";; esac
      return
    fi
  done
}
typew() { local w="$1" k; for ((k=0; k<${#w}; k++)); do letter "${w:$k:1}"; done; }
space() { tap "$(px 187.5)" "$R4"; }
shift_key() { tap "$(px 24)" "$R3"; }
del_key() { tap "$(px 351)" "$R3"; }
k123() { tap "$(px 23.25)" "$R4"; }
globe() { tap "$(px 70)" "$R4"; }
ret() { tap "$(px 328.25)" "$R4"; }

# Text of the focused / first EditText from a UI dump.
ui() { adb $D shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb $D exec-out cat /sdcard/u.xml; }
field_text() { ui | python -I -c "
import sys, xml.etree.ElementTree as ET
x = sys.stdin.buffer.read().decode('utf-8', 'replace')
x = x[x.find('<?xml'):] if '<?xml' in x else x[x.find('<hierarchy'):]
for n in ET.fromstring(x).iter('node'):
    if n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true':
        sys.stdout.reconfigure(encoding='utf-8'); print(n.get('text', '')); break
"; }
clear_field() { adb $D shell input keycombination 113 29 2>/dev/null; adb $D shell input keyevent 67; }
ime() { adb $D shell settings get secure default_input_method | tr -d '\r'; }

# Settings (debug builds): setp KEY true|false|NUMBER|STRING ; getp prints the stored prefs XML.
PKG=dev.leconnn.ioskeyboard
setp() {
  case "$2" in true|false) adb $D shell am broadcast -a $PKG.SET_PREF -p $PKG --es key "$1" --ez value "$2" >/dev/null ;;
               ''|*[!0-9]*) adb $D shell am broadcast -a $PKG.SET_PREF -p $PKG --es key "$1" --es value "$2" >/dev/null ;;
               *) adb $D shell am broadcast -a $PKG.SET_PREF -p $PKG --es key "$1" --ei value "$2" >/dev/null ;; esac
}
getp() { adb $D shell run-as $PKG cat /data/user_de/0/$PKG/shared_prefs/settings.xml; }
shown() { adb $D shell dumpsys input_method | grep -m1 -o 'mInputShown=true'; }
# Focus a text field at x,y and wait for the keyboard window.
focus() { local i; for i in 1 2 3 4 5; do tap "$1" "$2"; sleep 1; [ -n "$(shown)" ] && return 0; done; echo "keyboard did not show" >&2; return 1; }
# Field text with the hint treated as empty (pass the hint as $1).
ft() { local t; t=$(field_text); [ "$t" = "$1" ] && t=""; echo "$t"; }
# Back to the letters plane (space returns from 123/#+=) and an empty field.
reset() { space; clear_field; sleep 0.2; }

# Slide-to-type: touch down on the first letter, move through each key centre, lift on the last.
key_xy() {
  local rows=("qwertyuiop" "asdfghjkl" "zxcvbnm") c="$1" r i
  for r in 0 1 2; do
    i=$(expr index "${rows[$r]}" "$c")
    if [ "$i" -gt 0 ]; then
      i=$((i-1))
      case $r in 0) echo "$(r1 $i) $R1";; 1) echo "$(r2 $i) $R2";; 2) echo "$(r3 $i) $R3";; esac
      return
    fi
  done
}
swipe_word() {
  local w="$1" pts="" k
  for ((k=0; k<${#w}; k++)); do pts="$pts $(key_xy "${w:$k:1}")"; done
  # One adb shell for the whole gesture: separate adb calls are too slow (the long-press would fire).
  adb $D shell "$(python -I - $pts <<'PY'

import sys
v = list(map(int, sys.argv[1:])); pts = list(zip(v[0::2], v[1::2]))
cmds = ['input motionevent DOWN %d %d' % pts[0]]
for (ax, ay), (bx, by) in zip(pts, pts[1:]):
    for s in range(1, 5):
        t = s / 4
        cmds.append('input motionevent MOVE %d %d' % (ax + (bx - ax) * t, ay + (by - ay) * t))
cmds.append('input motionevent UP %d %d' % pts[-1])
print('; '.join(cmds))
PY
)"
}

# Precompile the app ahead of time (no JIT warm-up on first use). Run once after each install;
# Gboard gets the same effect from the baseline profile it ships through Play.
compile_app() { adb $D shell cmd package compile -m speed -f $PKG; }
