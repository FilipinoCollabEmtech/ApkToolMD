#include "Includes/ESPManager.h"
#include "Includes/Logger.h"
#include "Includes/obfuscate.h"

ESPManager::ESPManager() {}

ESPManager::~ESPManager() {}

void ESPManager::update(int screenWidth, int screenHeight) {
    (void)screenWidth;
    (void)screenHeight;
}

void ESPManager::render() {
    LOGD(OBFUSCATE("ESP render"));
}
