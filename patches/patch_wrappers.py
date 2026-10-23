p = "/tmp/uecn/AndUEDumper/src/UE/UEWrappers.cpp"
s = open(p).read()

# Cap the link-walk loops. With slightly-off struct offsets these chains can
# cycle and hang the dump thread forever (the game keeps running). Capping
# makes the dumper degrade gracefully instead of hanging.

subs = [
    # UE_UObject::GetPackageObject
    (
        "    UE_UObject package(nullptr);\n"
        "    for (auto outer = GetOuter(); outer; outer = outer.GetOuter())\n"
        "    {\n"
        "        package = outer;\n"
        "    }\n"
        "    return package;",
        "    UE_UObject package(nullptr);\n"
        "    int _g = 0;\n"
        "    for (auto outer = GetOuter(); outer && _g < 4096; outer = outer.GetOuter(), ++_g)\n"
        "    {\n"
        "        package = outer;\n"
        "    }\n"
        "    return package;",
    ),
    # UE_UObject::GetFullName
    (
        "    std::string temp;\n"
        "    for (auto outer = GetOuter(); outer; outer = outer.GetOuter())\n"
        "    {\n"
        "        temp = outer.GetName() + \".\" + temp;\n"
        "    }",
        "    std::string temp;\n"
        "    int _g = 0;\n"
        "    for (auto outer = GetOuter(); outer && _g < 4096; outer = outer.GetOuter(), ++_g)\n"
        "    {\n"
        "        temp = outer.GetName() + \".\" + temp;\n"
        "    }",
    ),
    # UE_UObject::GetCppName
    (
        "        for (auto c = Cast<UE_UStruct>(); c; c = c.GetSuper())\n",
        "        int _g = 0;\n"
        "        for (auto c = Cast<UE_UStruct>(); c && _g < 4096; c = c.GetSuper(), ++_g)\n",
    ),
    # UE_UObject::IsA
    (
        "    for (auto super = GetClass(); super; super = super.GetSuper().Cast<UE_UClass>())\n",
        "    int _g = 0;\n"
        "    for (auto super = GetClass(); super && _g < 4096; super = super.GetSuper().Cast<UE_UClass>(), ++_g)\n",
    ),
    # UE_UStruct::FindChildProp
    (
        "    for (auto prop = GetChildProperties(); prop; prop = prop.GetNext())\n",
        "    int _g = 0;\n"
        "    for (auto prop = GetChildProperties(); prop && _g < 65536; prop = prop.GetNext(), ++_g)\n",
    ),
    # UE_UStruct::FindChild
    (
        "    for (auto prop = GetChildren(); prop; prop = prop.GetNext())\n",
        "    int _g = 0;\n"
        "    for (auto prop = GetChildren(); prop && _g < 65536; prop = prop.GetNext(), ++_g)\n",
    ),
]

for a, b in subs:
    assert a in s, "wrappers anchor not found:\n" + a
    s = s.replace(a, b, 1)

open(p, "w").write(s)
print("patched UEWrappers.cpp (link-walk loops capped)")
