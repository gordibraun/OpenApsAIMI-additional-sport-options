#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/hid/IOHIDManager.h>
#include <IOKit/hid/IOHIDKeys.h>
#include <mach/mach_time.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Read-only host probe: exact cable identity, no HID output/feature requests. */
static const int vendor = 0x173a;
static const int product = 0x2106;
static unsigned reports;
static unsigned errors;
static uint64_t bytes;

static int number(IOHIDDeviceRef device, CFStringRef key) {
    int value = -1;
    CFTypeRef property = IOHIDDeviceGetProperty(device, key);
    if (property && CFGetTypeID(property) == CFNumberGetTypeID())
        CFNumberGetValue(property, kCFNumberIntType, &value);
    return value;
}

static void input(void *context, IOReturn result, void *sender,
                  IOHIDReportType type, uint32_t id, uint8_t *data, CFIndex length) {
    (void)context; (void)sender; (void)id; (void)data;
    if (result != kIOReturnSuccess || type != kIOHIDReportTypeInput || length < 0) {
        errors++;
        return;
    }
    /* Do not log payload: unsolicited IR data may contain private information. */
    reports++;
    bytes += (uint64_t)length;
}

int main(void) {
    IOHIDManagerRef manager = IOHIDManagerCreate(kCFAllocatorDefault, kIOHIDOptionsTypeNone);
    if (!manager) return 1;
    CFMutableDictionaryRef match = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFNumberRef vid = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &vendor);
    CFNumberRef pid = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &product);
    CFDictionarySetValue(match, CFSTR(kIOHIDVendorIDKey), vid);
    CFDictionarySetValue(match, CFSTR(kIOHIDProductIDKey), pid);
    IOHIDManagerSetDeviceMatching(manager, match);
    CFRelease(vid); CFRelease(pid); CFRelease(match);

    CFSetRef devices = IOHIDManagerCopyDevices(manager);
    CFIndex count = devices ? CFSetGetCount(devices) : 0;
    if (count != 1) {
        printf("{\"matchedDevices\":%ld,\"opened\":false}\n", (long)count);
        if (devices) CFRelease(devices);
        CFRelease(manager);
        return 2;
    }
    IOHIDDeviceRef device = NULL;
    CFSetGetValues(devices, (const void **)&device);
    int inputSize = number(device, CFSTR(kIOHIDMaxInputReportSizeKey));
    int outputSize = number(device, CFSTR(kIOHIDMaxOutputReportSizeKey));
    int featureSize = number(device, CFSTR(kIOHIDMaxFeatureReportSizeKey));
    if (number(device, CFSTR(kIOHIDVendorIDKey)) != vendor ||
        number(device, CFSTR(kIOHIDProductIDKey)) != product ||
        number(device, CFSTR(kIOHIDPrimaryUsagePageKey)) != 0xff00 ||
        number(device, CFSTR(kIOHIDPrimaryUsageKey)) != 1 || inputSize != 64) {
        fprintf(stderr, "Unexpected HID identity or input layout; not opened\n");
        CFRelease(devices); CFRelease(manager);
        return 3;
    }
    IOReturn opened = IOHIDDeviceOpen(device, kIOHIDOptionsTypeNone);
    printf("{\"matchedDevices\":1,\"vendorId\":5946,\"productId\":8454,"
           "\"inputBytes\":%d,\"outputBytes\":%d,\"featureBytes\":%d,"
           "\"openResult\":\"0x%08x\",\"opened\":%s,\"exclusive\":false}\n",
           inputSize, outputSize, featureSize, (unsigned)opened,
           opened == kIOReturnSuccess ? "true" : "false");
    fflush(stdout);
    if (opened != kIOReturnSuccess) {
        CFRelease(devices); CFRelease(manager);
        return 4;
    }
    uint8_t buffer[64] = {0};
    IOHIDDeviceRegisterInputReportCallback(device, buffer, sizeof(buffer), input, NULL);
    IOHIDDeviceScheduleWithRunLoop(device, CFRunLoopGetCurrent(), kCFRunLoopDefaultMode);
    mach_timebase_info_data_t scale;
    mach_timebase_info(&scale);
    uint64_t start = mach_absolute_time();
    double elapsed = 0;
    do {
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.1, true);
        elapsed = (double)(mach_absolute_time() - start) * scale.numer / scale.denom / 1e9;
    } while (elapsed < 5.0);
    IOHIDDeviceUnscheduleFromRunLoop(device, CFRunLoopGetCurrent(), kCFRunLoopDefaultMode);
    IOReturn closed = IOHIDDeviceClose(device, kIOHIDOptionsTypeNone);
    printf("{\"passiveSeconds\":%.3f,\"inputReports\":%u,\"inputBytes\":%llu,"
           "\"callbackErrors\":%u,\"applicationOutputReports\":0,"
           "\"featureRequests\":0,\"closeResult\":\"0x%08x\"}\n",
           elapsed, reports, (unsigned long long)bytes, errors, (unsigned)closed);
    CFRelease(devices); CFRelease(manager);
    return closed == kIOReturnSuccess && errors == 0 ? 0 : 5;
}
