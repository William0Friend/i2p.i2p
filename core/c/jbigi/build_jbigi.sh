#!/bin/sh
# When executed in Mingw: Produces a jbigi.dll
# When executed in Linux/FreeBSD: Produces a libjbigi.so
# When executed in OSX: Produces a libjbigi.jnilib
[ -z "$CC" ] && CC="gcc"

if [ -z $BITS ]; then
  if [[ $(uname -a) =~ "x86_64" ]]; then
    BITS=64
  elif [[ $(uname -a) =~ "i386" ]]; then
    BITS=32
  elif [[ $(uname -a) =~ "i686" ]]; then
    BITS=32
  else
    echo "Unable to detect default setting for BITS variable"
    exit
  fi

  printf "\aBITS variable not set, defaulting to $BITS\n\a" >&2
fi

# If JAVA_HOME isn't set we'll try to figure it out
[ -z $JAVA_HOME ] && . `dirname $0`/../find-java-home
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'"
    echo "Please set JAVA_HOME to a java home that has the JNI"
    exit 1
fi


# Allow TARGET to be overridden (e.g. for use with cross compilers)
[ -z $TARGET ] && TARGET=$(uname -s | tr "[A-Z]" "[a-z]")

# Note, this line does not support windows (and needs to generate a win32/win64 string for that to work)
BUILD_OS=$(uname -s | tr "[A-Z]" "[a-z]")
echo "TARGET=$TARGET"

case "$TARGET" in
mingw*|windows*)
        COMPILEFLAGS="-Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/$BUILD_OS -I/usr/local/include"
        LINKFLAGS="-shared -Wl,--kill-at"
        LIBFILE="jbigi.dll";;
cygwin*)
        COMPILEFLAGS="-Wall -mno-cygwin"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include/$BUILD_OS/ -I$JAVA_HOME/include/"
        LINKFLAGS="-shared -Wl,--kill-at"
        LIBFILE="jbigi.dll";;
darwin*|osx)
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/$BUILD_OS -I/usr/local/include"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="libjbigi.jnilib";;
sunos*|openbsd*|netbsd*|*freebsd*|linux*)
        if [ $BUILD_OS = "sunos" ]; then
            BUILD_OS="solaris"
        elif [ $BUILD_OS = "gnu/kfreebsd" ]; then
            BUILD_OS="linux"
        fi
        COMPILEFLAGS="-fPIC -Wall $CFLAGS"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/$BUILD_OS -I/usr/local/include"
        LINKFLAGS="-shared -Wl,-soname,libjbigi.so"
        LIBFILE="libjbigi.so";;
*)
        echo "Unsupported system type."
        exit 1;;
esac

if [ "$1" = "dynamic" ] ; then
        echo "Building a jbigi lib that is dynamically linked to GMP"
        LIBPATH="-L.libs -L/usr/local/lib"
        INCLUDELIBS="-lgmp"
else
        echo "Building a jbigi lib that is statically linked to GMP"
        STATICLIBS=".libs/libgmp.a"
fi

[ $BITS -eq 32 ] && COMPILEFLAGS="-m32 $COMPILEFLAGS" && LINKFLAGS="-m32 $LINKFLAGS"
[ $BITS -eq 64 ] && COMPILEFLAGS="-m64 $COMPILEFLAGS" && LINKFLAGS="-m64 $LINKFLAGS"

echo "Compiling C code..."
echo "Compile: \"$CC -c $COMPILEFLAGS $INCLUDES ../../jbigi/src/jbigi.c\""
$CC -c $COMPILEFLAGS $INCLUDES ../../jbigi/src/jbigi.c || exit 1
echo "Link: \"$CC $LINKFLAGS $INCLUDES -o $LIBFILE jbigi.o $INCLUDELIBS $STATICLIBS $LIBPATH\""
$CC $LINKFLAGS $INCLUDES -o $LIBFILE jbigi.o $INCLUDELIBS $STATICLIBS $LIBPATH || exit 1

exit 0
