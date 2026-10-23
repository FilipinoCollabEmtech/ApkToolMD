p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

# NOTE: the pristine file already contains these same strings *inside a
# commented-out /* ... */ block, so a plain `not in s` guard would skip the
# insertion. Check for an activated (adjacent) occurrence instead.
inc_anchor = '#include "UE/UEGameProfiles/Valorant.hpp"'
assert inc_anchor in s, "Valorant include not found"
inc_new = inc_anchor + '\n#include "UE/UEGameProfiles/SFG2.hpp"'
if inc_new not in s:
    s = s.replace(inc_anchor, inc_new, 1)

reg_anchor = "    new ValorantProfile(),"
assert reg_anchor in s, "Valorant registration not found"
reg_new = reg_anchor + "\n    new SFG2Profile(),"
if reg_new not in s:
    s = s.replace(reg_anchor, reg_new, 1)

assert inc_new in s, "SFG2 include was not activated"
assert reg_new in s, "SFG2 registration was not activated"
open(p, "w").write(s)
print("patched library.cpp (SFG2 profile wired in)")
