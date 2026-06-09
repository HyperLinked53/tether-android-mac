BUNDLE    := ConduitMicrophone.driver
CONTENTS  := $(BUNDLE)/Contents
MACOS_DIR := $(CONTENTS)/MacOS
BINARY    := $(MACOS_DIR)/ConduitMicrophone
PLIST_DST := $(CONTENTS)/Info.plist
HAL_DIR   := $(HOME)/Library/Audio/Plug-Ins/HAL
SDK       := $(shell xcrun --show-sdk-path 2>/dev/null)
ARCH      := $(shell uname -m)

# Build universal binary if running on Apple Silicon (supports both native + Rosetta).
ifeq ($(ARCH),arm64)
  ARCHS = -arch arm64 -arch x86_64
else
  ARCHS = -arch x86_64
endif

CFLAGS := -std=c11 -O2 $(ARCHS) \
           -isysroot $(SDK) \
           -mmacosx-version-min=11.0 \
           -fvisibility=hidden \
           -bundle \
           -framework CoreFoundation \
           -framework CoreAudio

.PHONY: all build install uninstall clean

all: build

build: $(BINARY) $(PLIST_DST)

$(BINARY): ConduitMicrophone.c
	@mkdir -p $(MACOS_DIR)
	xcrun clang $(CFLAGS) -o $@ $<
	@echo "Built $(BINARY)"

$(PLIST_DST): Info.plist
	@mkdir -p $(CONTENTS)
	cp Info.plist $@

install: build
	@mkdir -p "$(HAL_DIR)"
	cp -r "$(BUNDLE)" "$(HAL_DIR)/"
	@echo ""
	@echo "✓ Installed to $(HAL_DIR)/$(BUNDLE)"
	@echo ""
	@echo "Restart coreaudiod to activate:"
	@echo "  sudo killall coreaudiod"
	@echo ""
	@echo "Then select 'Conduit Microphone' in Zoom / FaceTime / System Settings → Sound → Input."

uninstall:
	rm -rf "$(HAL_DIR)/$(BUNDLE)"
	@echo "Removed. Restart coreaudiod: sudo killall coreaudiod"

clean:
	rm -rf $(BUNDLE)
