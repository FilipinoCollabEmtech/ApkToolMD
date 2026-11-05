#ifndef OBFUSCATE_H
#define OBFUSCATE_H

// Simple XOR obfuscation for string literals at compile time
#define OBFUSCATE(str) ::ObfuscateString(str).decrypt()

class ObfuscateString {
public:
    explicit ObfuscateString(const char *str) {
        size_t len = 0;
        while (str[len]) len++;
        m_length = len;
        m_data = new char[len + 1];
        for (size_t i = 0; i < len; i++) {
            m_data[i] = str[i] ^ 0x5A;
        }
        m_data[len] = '\0';
    }
    ~ObfuscateString() { delete[] m_data; }

    const char *decrypt() const {
        static thread_local char buffer[4096];
        for (size_t i = 0; i <= m_length; i++) {
            buffer[i] = m_data[i] ^ 0x5A;
        }
        return buffer;
    }

private:
    char *m_data;
    size_t m_length;
};

#endif
