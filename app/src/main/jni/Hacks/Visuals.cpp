//
// Visuals rendering (ESP, crosshair, etc.)
//

#include "Includes/Logger.h"
#include "Includes/obfuscate.h"
#include "Includes/ESPManager.h"

ESPManager::ESPManager() {}

ESPManager::~ESPManager() {}

void ESPManager::update(int screenWidth, int screenHeight) {
    (void)screenWidth;
    (void)screenHeight;
}

void ESPManager::render() {
    LOGD(OBFUSCATE("ESP render"));
}
