# Makefile — build + install the Conduit Virtual Camera CMIO DAL plugin
#
# Usage:
#   make          — compile the plugin bundle
#   make install  — copy to ~/Library/CoreMediaIO/Plug-Ins/DAL/ and kill/restart
#                   any camera process that caches the device list
#   make clean    — remove build artefacts

PLUGIN    := ConduitVirtualCamera.plugin
BINARY    := $(PLUGIN)/Contents/MacOS/ConduitVirtualCamera
PLIST     := $(PLUGIN)/Contents/Info.plist
SRC       := ConduitVirtualCamera.mm

SDK       := $(shell xcrun --show-sdk-path)
ARCH      := $(shell uname -m)   # arm64 on Apple Silicon, x86_64 on Intel

CXXFLAGS  := -std=c++17 -ObjC++ -arch $(ARCH) \
             -isysroot $(SDK) \
             -mmacosx-version-min=12.0 \
             -fvisibility=hidden \
             -Wall -Wno-deprecated-declarations

LDFLAGS   := -bundle \
             -framework CoreMediaIO \
             -framework CoreMedia \
             -framework CoreVideo \
             -framework VideoToolbox \
             -framework Foundation \
             -framework CoreFoundation

DAL_DIR   := $(HOME)/Library/CoreMediaIO/Plug-Ins/DAL

.PHONY: all install clean

all: $(BINARY) $(PLIST)

$(BINARY): $(SRC)
	@mkdir -p $(PLUGIN)/Contents/MacOS
	xcrun clang $(CXXFLAGS) $(LDFLAGS) -o $@ $<
	@echo "Built $@"

$(PLIST): Info.plist
	@mkdir -p $(PLUGIN)/Contents
	cp Info.plist $@

install: all
	@mkdir -p "$(DAL_DIR)"
	rm -rf "$(DAL_DIR)/$(PLUGIN)"
	cp -R $(PLUGIN) "$(DAL_DIR)/"
	@echo "Installed to $(DAL_DIR)/$(PLUGIN)"
	@echo ""
	@echo "Restart any open camera apps (Zoom, FaceTime, Photo Booth) to pick up the new device."
	@# Kill VDCAssistant so it re-scans the DAL directory
	@-pkill -x VDCAssistant 2>/dev/null; true
	@-pkill -x CMIODaemonHelper 2>/dev/null; true
	@echo "VDCAssistant restarted — new device will appear in ~5 seconds."

uninstall:
	rm -rf "$(DAL_DIR)/$(PLUGIN)"
	@echo "Uninstalled."
	@-pkill -x VDCAssistant 2>/dev/null; true

clean:
	rm -rf $(PLUGIN)
