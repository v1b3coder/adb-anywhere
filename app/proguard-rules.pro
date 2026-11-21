# Project-specific R8 rules.
#
# Conscrypt ships adapters for the platform TLS stack of older Android releases.
# Those classes do not exist on the platforms this app runs on, so R8 reports
# them as missing and refuses to finish. R8's own generated list, from
# app/build/outputs/mapping/<variant>/missing_rules.txt.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
