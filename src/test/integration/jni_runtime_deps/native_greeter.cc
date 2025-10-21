#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_jni_1runtime_1deps_NativeGreeter_greet(JNIEnv* env, jobject /* this */) {
    std::string greeting = "Hello from JNI!";
    return env->NewStringUTF(greeting.c_str());
}
