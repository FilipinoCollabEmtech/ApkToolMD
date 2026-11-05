#ifndef MEMORYPATCH_H
#define MEMORYPATCH_H

#include "KittyMemory.h"
#include <cstdint>
#include <cstddef>
#include <vector>

namespace KittyMemory {

class MemoryPatch {
public:
    MemoryPatch(uintptr_t address, const void *patchBytes, size_t patchSize);
    ~MemoryPatch();

    bool patch();
    bool restore();
    bool isPatched() const;

    uintptr_t getAddress() const { return m_address; }
    size_t getSize() const { return m_size; }

private:
    uintptr_t m_address;
    size_t m_size;
    std::vector<uint8_t> m_originalBytes;
    std::vector<uint8_t> m_patchBytes;
    bool m_isPatched;
    bool m_hasOriginal;
};

} // namespace KittyMemory

#endif
