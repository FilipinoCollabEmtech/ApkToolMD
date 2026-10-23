p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

anchor = '    write_status("DONE: dump complete");\n    show_notification("UEDump3r: dump complete");\n'
assert anchor in s, "done anchor not found"

mod_code = anchor + r'''
    // ---- Mod thread (diagnostic, read-only): prove in-process state access ----
    plog("MOD: starting diagnostic thread");
    std::thread([](){
        sleep(4);
        // Resolve our own ELF base so we can read globals by RVA.
        uintptr_t base = 0;
        {
            // use the SFG2 profile's ELF if available
            for (auto &it : UE_Games)
            {
                auto ue = it->GetUnrealELF();
                if (ue.isValid()) { base = ue.base(); break; }
            }
        }
        plog("MOD: elf base = 0x%lx", (unsigned long)base);
        if (base == 0) { plog("MOD: no elf base, abort"); return; }

        // GWorld global RVA from dump: 0x493CE00
        uintptr_t gworldPtrAddr = base + 0x493CE00;
        for (int i = 0; i < 20; i++)
        {
            uintptr_t gworld = vm_rpm_ptr<uintptr_t>((void*)gworldPtrAddr);
            plog("MOD: iter %d GWorld=0x%lx", i, (unsigned long)gworld);

            // Find any PlayerController instance among UObjects
            int pcCount = 0;
            UEWrappers::GetObjects()->ForEachObject([&pcCount](UE_UObject obj){
                if (!obj) return false;
                std::string cls = obj.GetClass().GetName();
                if (cls.find("PlayerController") != std::string::npos)
                {
                    pcCount++;
                    if (pcCount <= 3)
                        plog("MOD: PlayerController @ 0x%lx (%s)", (unsigned long)obj.GetAddress(), obj.GetFullName().c_str());
                }
                return false;
            });
            plog("MOD: PlayerController instances = %d", pcCount);

            sleep(3);
        }
        plog("MOD: diagnostic thread done");
    }).detach();
'''
s = s.replace(anchor, mod_code, 1)

open(p, "w").write(s)
print("patched mod diagnostic thread into library.cpp")
