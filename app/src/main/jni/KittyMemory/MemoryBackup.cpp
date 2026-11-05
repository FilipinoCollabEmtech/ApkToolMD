#include "MemoryBackup.h"

namespace KittyMemory {

MemoryBackup::MemoryBackup(uintptr_t address, size_t size)
    : m_address(address), m_size(size), m_hasBackup(false) {}

MemoryBackup::~MemoryBackup() {
    if (m_hasBackup) {
        restore();
    }
}

bool MemoryBackup::backup() {
    if (m_hasBackup) return true;
    m_backupBytes.resize(m_size);
    if (readMemory(m_address, m_backupBytes.data(), m_size) != (ssize_t)m_size) {
        return false;
    }
    m_hasBackup = true;
    return true;
}

bool MemoryBackup::restore() {
    if (!m_hasBackup) return true;
    if (writeMemory(m_address, m_backupBytes.data(), m_size) != (ssize_t)m_size) {
        return false;
    }
    m_hasBackup = false;
    return true;
}

bool MemoryBackup::isBackedUp() const {
    return m_hasBackup;
}

} // namespace KittyMemory
