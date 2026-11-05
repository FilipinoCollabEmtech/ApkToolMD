#include "MemoryPatch.h"

namespace KittyMemory {

MemoryPatch::MemoryPatch(uintptr_t address, const void *patchBytes, size_t patchSize)
    : m_address(address), m_size(patchSize), m_isPatched(false), m_hasOriginal(false) {
    m_patchBytes.assign(static_cast<const uint8_t *>(patchBytes),
                        static_cast<const uint8_t *>(patchBytes) + patchSize);
}

MemoryPatch::~MemoryPatch() {
    if (m_isPatched) {
        restore();
    }
}

bool MemoryPatch::patch() {
    if (m_isPatched) return true;
    m_originalBytes.resize(m_size);
    if (readMemory(m_address, m_originalBytes.data(), m_size) != (ssize_t)m_size) {
        return false;
    }
    m_hasOriginal = true;
    if (writeMemory(m_address, m_patchBytes.data(), m_size) != (ssize_t)m_size) {
        return false;
    }
    m_isPatched = true;
    return true;
}

bool MemoryPatch::restore() {
    if (!m_isPatched || !m_hasOriginal) return true;
    if (writeMemory(m_address, m_originalBytes.data(), m_size) != (ssize_t)m_size) {
        return false;
    }
    m_isPatched = false;
    return true;
}

bool MemoryPatch::isPatched() const {
    return m_isPatched;
}

} // namespace KittyMemory
