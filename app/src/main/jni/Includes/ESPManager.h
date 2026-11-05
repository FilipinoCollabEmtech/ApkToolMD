#ifndef ESPMANAGER_H
#define ESPMANAGER_H

class ESPManager {
public:
    ESPManager();
    ~ESPManager();

    void update(int screenWidth, int screenHeight);
    void render();
};

#endif
