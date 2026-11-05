//
// Created by rosetta on 13/09/2024.
//

#include "FeatureModule.hpp"
#include "Includes/Logger.h"
#include "Includes/obfuscate.h"

// Forward declare Widget system (defined in the menu framework)
struct Widget {
    static void Clear();
    static void Add(const char *tabName, const char *tabIcon, const char *childrenJson);
    static std::string ToJsonString();
};

void FeatureModule::OnDraw(JNIEnv *env, jclass clazz, jobject draw_view, jobject canvas) {
    LOGD(OBFUSCATE("OnDraw called"));
}

jstring FeatureModule::GetFeatureList(JNIEnv *env, jobject context) {
    Widget::Clear();
    RegisterFeatures();
    std::string json = Widget::ToJsonString();
    return env->NewStringUTF(json.c_str());
}

void FeatureModule::RegisterFeatures() {
    // Player tab
    Widget::Add(OBFUSCATE("Player"), OBFUSCATE("grid"), OBFUSCATE(
        "["
        "  {\"type\":\"ITextView\",\"name\":\"Welcome to <font color=#EC4857>R3D Network ID</font> mod menu! Here you can customize your gameplay experience.\"},"
        "  {\"type\":\"ISwitch\",\"id\":4,\"name\":\"God Mode\",\"desc\":\"Player will not get any damage except fall from the map\",\"value\":false},"
        "  {\"type\":\"IRadioButton\",\"id\":8,\"name\":\"Difficulty\",\"options\":[\"Easy\",\"Normal\",\"Hard\"],\"value\":0},"
        "  {\"type\":\"IInputFloat\",\"id\":10,\"name\":\"Speed Multiplier\",\"value\":10.0},"
        "  {\"type\":\"IButton\",\"id\":9,\"name\":\"Print Player Data\"}"
        "]"));

    // Visuals tab
    Widget::Add(OBFUSCATE("Visuals"), OBFUSCATE("eye"), OBFUSCATE(
        "["
        "  {\"type\":\"ICheckBox\",\"id\":1,\"name\":\"ESP Crosshair\",\"desc\":\"Display a crosshair in the center of the screen\",\"value\":false},"
        "  {\"type\":\"ICollapse\",\"name\":\"Crosshair Settings\",\"children\":["
        "    {\"type\":\"ISpinner\",\"id\":2,\"name\":\"Crosshair Color\",\"options\":[\"Red\",\"Green\",\"Blue\"],\"value\":0},"
        "    {\"type\":\"ISlider\",\"id\":3,\"name\":\"Crosshair Size\",\"min\":10,\"max\":100,\"value\":10}"
        "  ]"
        "]"));

    // Profile tab
    Widget::Add(OBFUSCATE("Profile"), OBFUSCATE("user"), OBFUSCATE(
        "["
        "  {\"type\":\"ICategory\",\"name\":\"Player\"},"
        "  {\"type\":\"IInputText\",\"id\":5,\"name\":\"Player Name\"},"
        "  {\"type\":\"IInputInt\",\"id\":6,\"name\":\"Player Age\",\"value\":120},"
        "  {\"type\":\"ICategory\",\"name\":\"Links\"},"
        "  {\"type\":\"IButtonLink\",\"id\":7,\"name\":\"Visit Website\",\"url\":\"https://github.com/seedhollow/R3DNETWORK\"}"
        "]"));
}

void FeatureModule::OnFeatureChanged(JNIEnv *env, jclass clazz, jobject obj, jint featNum,
                                     jstring featName, jint value, jfloat floatValue,
                                     jboolean boolean, jstring str) {
    const char *name = env->GetStringUTFChars(featName, nullptr);
    const char *strVal = str != nullptr ? env->GetStringUTFChars(str, nullptr) : "";

    LOGD(OBFUSCATE("Feature changed: [%d] %s | value=%d | float=%f | bool=%d | str=%s"),
         featNum, name, value, floatValue, boolean, strVal);

    switch (featNum) {
        case 1: break; // ESPCrosshair
        case 2: break; // CrosshairColor
        case 3: break; // CrosshairSize
        case 4: break; // godMode
        case 5: LOGD(OBFUSCATE("Player Name changed to: %s"), strVal); break;
        case 6: LOGD(OBFUSCATE("Player Age changed to: %d"), value); break;
        case 7: LOGD(OBFUSCATE("Button Link clicked: %s"), strVal); break;
        case 8: LOGD(OBFUSCATE("Difficulty changed to: %d"), value); break;
        case 9:
            LOGI(OBFUSCATE("God Mode=%d | ESP Crosshair=%d | Crosshair Color=%d | Crosshair Size=%d | Speed Multiplier=%f"),
                 0, 0, 0, 0, floatValue);
            break;
        case 10: break; // SpeedMultiplier
        default: break;
    }

    env->ReleaseStringUTFChars(featName, name);
    if (str != nullptr) env->ReleaseStringUTFChars(str, strVal);
}
