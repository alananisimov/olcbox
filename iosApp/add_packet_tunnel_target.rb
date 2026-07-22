#!/usr/bin/env ruby
# Adds the PacketTunnel (NEPacketTunnelProvider) app-extension target to the
# Olcbox iOS project, links the hev + olcRTC xcframeworks into it, embeds it in
# the app, and wires entitlements/app-group. Idempotent: re-running removes an
# existing PacketTunnel target first.
require 'xcodeproj'

PROJECT   = File.expand_path(File.join(__dir__, 'iosApp.xcodeproj'))
APP_NAME  = 'iosApp'
EXT_NAME  = 'PacketTunnel'
EXT_BID   = 'org.olcbox.app.ios.PacketTunnel'
APP_GROUP = 'group.org.olcbox.app'

proj = Xcodeproj::Project.open(PROJECT)
app  = proj.targets.find { |t| t.name == APP_NAME } or abort("app target #{APP_NAME} not found")

# --- clean any previous run -------------------------------------------------
proj.targets.select { |t| t.name == EXT_NAME }.each do |old|
  app.dependencies.select { |d| d.target == old }.each(&:remove_from_project)
  old.remove_from_project
end

# embed phase, extension product, and every file reference a previous run added
# to the app side — removing only the target (as before) left all of these
# behind and duplicated them on each re-run.
app.build_phases.grep(Xcodeproj::Project::Object::PBXCopyFilesBuildPhase)
   .select { |ph| ph.name == 'Embed App Extensions' }
   .each(&:remove_from_project)
proj.products_group.files
    .select { |f| f.display_name == "#{EXT_NAME}.appex" }
    .each(&:remove_from_project)
stale_paths = [
  'PacketTunnel/PacketTunnelProvider.swift',
  'PacketTunnel/Info.plist',
  'PacketTunnel/PacketTunnel.entitlements',
  'iosApp/SwiftTunnelManager.swift',
  'Frameworks/HevSocks5Tunnel.xcframework',
  '../sharedUI/build/generated/olcrtc/ios/OlcRtcMobile.xcframework',
]
proj.files.select { |f| stale_paths.include?(f.path) }.each do |f|
  f.build_files.each(&:remove_from_project)
  f.remove_from_project
end
# Foundation.framework ref that new_target adds for the extension (upstream has none)
proj.files.select { |f| f.display_name == 'Foundation.framework' }.each do |f|
  f.build_files.each(&:remove_from_project)
  f.remove_from_project
end

# --- create the extension target -------------------------------------------
ext = proj.new_target(:app_extension, EXT_NAME, :ios, '15.0', proj.products_group, :swift)

ext.build_configurations.each do |c|
  bs = c.build_settings
  bs['PRODUCT_BUNDLE_IDENTIFIER']            = EXT_BID
  bs['PRODUCT_NAME']                         = '$(TARGET_NAME)'
  bs['INFOPLIST_FILE']                       = 'PacketTunnel/Info.plist'
  bs['CODE_SIGN_ENTITLEMENTS']               = 'PacketTunnel/PacketTunnel.entitlements'
  bs['GENERATE_INFOPLIST_FILE']              = 'NO'
  bs['SWIFT_VERSION']                        = '6.0'
  bs['TARGETED_DEVICE_FAMILY']               = '1,2'
  bs['IPHONEOS_DEPLOYMENT_TARGET']           = '15.0'
  bs['CODE_SIGNING_ALLOWED']                 = 'NO'
  bs['CODE_SIGN_STYLE']                      = 'Automatic'
  bs['SKIP_INSTALL']                         = 'YES'
  bs['ENABLE_BITCODE']                       = 'NO'
  bs['ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES'] = 'NO'
  bs['SWIFT_OBJC_INTEROP_MODE']              = 'objcxx' rescue nil
  # -lresolv + Security/CoreFoundation: the gomobile OlcRtcMobile Go runtime needs
  # libresolv (res_9_* DNS symbols) and these system frameworks. The app gets them
  # transitively via the Kotlin/Native SharedUI framework; the extension links neither.
  bs['OTHER_LDFLAGS'] = ['$(inherited)', '-ObjC', '-lresolv', '-framework', 'Security', '-framework', 'CoreFoundation']
end

# --- source files -----------------------------------------------------------
provider_ref = proj.main_group.new_file('PacketTunnel/PacketTunnelProvider.swift')
ext.source_build_phase.add_file_reference(provider_ref)
# Info.plist / entitlements as references (visible in Xcode, not compiled)
proj.main_group.new_file('PacketTunnel/Info.plist')
proj.main_group.new_file('PacketTunnel/PacketTunnel.entitlements')

# app-side controller into the app target
tun_mgr_ref = proj.main_group.new_file('iosApp/SwiftTunnelManager.swift')
app.source_build_phase.add_file_reference(tun_mgr_ref)

# --- link xcframeworks into the extension -----------------------------------
fw_group = proj.main_group.find_subpath('Frameworks', true)
hev_ref  = fw_group.new_file('Frameworks/HevSocks5Tunnel.xcframework')
# olcRTC xcframework is gradle-generated; reference the (future) build path.
olc_ref  = fw_group.new_file('../sharedUI/build/generated/olcrtc/ios/OlcRtcMobile.xcframework')
[hev_ref, olc_ref].each { |r| ext.frameworks_build_phase.add_file_reference(r) }

# --- pre-build: ensure both native xcframeworks exist before compiling -------
gradle_phase = ext.new_shell_script_build_phase('Build Native Frameworks')
gradle_phase.shell_script = <<~SH
  set -e
  export PATH="$HOME/go/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
  if [ -z "$ARCHS" ] || [ "$ARCHS" = "undefined_arch" ]; then export ARCHS=arm64; fi
  if [ ! -d "$SRCROOT/Frameworks/HevSocks5Tunnel.xcframework" ]; then
    "$SRCROOT/../androidApp/src/main/jni/hev-socks5-tunnel/build-apple-ios.sh"
  fi
  cd "$SRCROOT/.."
  ./gradlew :sharedUI:buildOlcrtcIosXcframework --no-configuration-cache
SH
# run it first, before Sources
ext.build_phases.unshift(ext.build_phases.delete(gradle_phase))

# --- embed extension into the app + dependency ------------------------------
app.add_dependency(ext)
embed = app.new_copy_files_build_phase('Embed App Extensions')
embed.symbol_dst_subfolder_spec = :plug_ins
appex = ext.product_reference
build_file = embed.add_file_reference(appex)
build_file.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }

# --- app entitlements (network extension + app group) -----------------------
app.build_configurations.each do |c|
  c.build_settings['CODE_SIGN_ENTITLEMENTS'] = 'iosApp/iosApp.entitlements'
end

proj.save
puts "OK: added #{EXT_NAME} target, embedded in #{APP_NAME}"
puts "targets now: #{proj.targets.map(&:name).join(', ')}"
