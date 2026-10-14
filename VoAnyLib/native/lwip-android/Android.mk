LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LWIP_ROOT := $(LOCAL_PATH)/../../.upstream/lwip
LOCAL_MODULE := voanylwip
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include $(LWIP_ROOT)/src/include $(LWIP_ROOT)/contrib/ports/unix/port/include
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LWIP_ROOT)/src/core/*.c) $(wildcard $(LWIP_ROOT)/src/core/ipv4/*.c) $(wildcard $(LWIP_ROOT)/src/core/ipv6/*.c) $(wildcard $(LWIP_ROOT)/src/api/*.c)) ../../.upstream/lwip/contrib/ports/unix/port/sys_arch.c voany_lwip.c
LOCAL_CFLAGS := -Wall -Wno-unused-parameter
LOCAL_LDLIBS := -llog
include $(BUILD_STATIC_LIBRARY)
