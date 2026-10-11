LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libterminal-jni
LOCAL_SRC_FILES:= terminal-jni.c
# NDK r27 and older need explicit flexible-page alignment. Without this,
# The JNI library cannot be loaded reliably on Android 15+ 16 KiB-page devices.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
