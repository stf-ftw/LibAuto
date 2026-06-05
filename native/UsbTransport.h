#pragma once

#include <cstdint>
#include <string>

class UsbTransport {
public:
    bool OpenByVidPid(int vid, int pid);
    bool OpenByDeviceName(const std::string& device_name);
    int Read(uint8_t* buffer, int length, int timeout_ms);
    int Write(const uint8_t* buffer, int length, int timeout_ms);
    void Close();
    void NotifyTransportStalled();

private:
    bool first_read_logged_ = false;
    bool first_write_logged_ = false;
};

bool InitUsbJniBridge(void* env);
