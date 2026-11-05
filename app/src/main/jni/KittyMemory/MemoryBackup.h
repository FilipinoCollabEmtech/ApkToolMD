#ifndef MEMORYBACKUP_H
#define MEMORYBACKUP_H

#include "KittyMemory.h"
#include <cstdint>
#include <cstddef>
#include <vector>

namespace KittyMemory {

class MemoryBackup {
public:
    MemoryBackup(uintptr_t address, size_t size);
    ~MemoryBackup();

    bool backup();
    bool restore();
    bool isBackedUp() const;

    uintptr_t getAddress() const { return m_address; }
    size_t getSize() const { return m_size; }

private:
    uintptr_t m_address;
    size_t m_size;
    std::vector<uint8_t> m_backupBytes;
    bool m_hasBackup;
};

} // namespace KittyMemory

#endif
