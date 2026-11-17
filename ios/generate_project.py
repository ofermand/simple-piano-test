#!/usr/bin/env python3
"""Regenerate the checked-in Xcode project using only Python's standard library."""
from pathlib import Path
import hashlib
root = Path(__file__).resolve().parent
objects = []
def uid(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def add(name, value):
    objects.append(f'{uid(name)} = {{ {value} }};')
    return uid(name)
def refs(names): return '(' + ', '.join(uid(n) for n in names) + ',)'
files = sorted([*root.glob('App/*.swift'), *root.glob('Sources/PianoCore/*.swift')])
for path in files:
    relative = path.relative_to(root).as_posix()
    add(relative, f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{relative}"; sourceTree = "<group>";')
    add('build:'+relative, f'isa = PBXBuildFile; fileRef = {uid(relative)};')
add('assets', 'isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = App/Assets.xcassets; sourceTree = "<group>";')
add('assets-build', f'isa = PBXBuildFile; fileRef = {uid("assets")};')
add('privacy', 'isa = PBXFileReference; lastKnownFileType = text.xml; path = App/PrivacyInfo.xcprivacy; sourceTree = "<group>";')
add('privacy-build', f'isa = PBXBuildFile; fileRef = {uid("privacy")};')
add('plist', 'isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = App/Info.plist; sourceTree = "<group>";')
add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; path = SimplePiano.app; sourceTree = BUILT_PRODUCTS_DIR; includeInIndex = 0;')
add('products', f'isa = PBXGroup; children = {refs(["product"])}; name = Products; sourceTree = "<group>";')
add('root-group', f'isa = PBXGroup; children = {refs([str(p.relative_to(root)) for p in files]+["assets","privacy","plist","products"])}; sourceTree = "<group>";')
add('sources', f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {refs(["build:"+str(p.relative_to(root)) for p in files])}; runOnlyForDeploymentPostprocessing = 0;')
add('resources', f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {refs(["assets-build","privacy-build"])}; runOnlyForDeploymentPostprocessing = 0;')
add('frameworks', 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
for mode in ['Debug','Release']:
    project_settings = 'CLANG_ENABLE_MODULES = YES; SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SWIFT_VERSION = 5.0;'
    project_settings += ' SWIFT_OPTIMIZATION_LEVEL = "-Onone"; DEBUG_INFORMATION_FORMAT = dwarf;' if mode=='Debug' else ' SWIFT_COMPILATION_MODE = wholemodule; SWIFT_OPTIMIZATION_LEVEL = "-O"; DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";'
    add('project-'+mode, f'isa = XCBuildConfiguration; buildSettings = {{ {project_settings} }}; name = {mode};')
    target_settings = '''PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = com.simplepianotest.ios;
        INFOPLIST_FILE = App/Info.plist; GENERATE_INFOPLIST_FILE = NO; CODE_SIGN_STYLE = Automatic;
        MARKETING_VERSION = 1.0; CURRENT_PROJECT_VERSION = 1; TARGETED_DEVICE_FAMILY = 1;
        SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO;
        ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; ENABLE_USER_SCRIPT_SANDBOXING = YES;
        SWIFT_EMIT_LOC_STRINGS = YES; LD_RUNPATH_SEARCH_PATHS = "$(inherited) @executable_path/Frameworks";'''
    if mode=='Debug': target_settings += ' SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;'
    add('target-'+mode, f'isa = XCBuildConfiguration; buildSettings = {{ {target_settings} }}; name = {mode};')
for kind in ['project','target']:
    add(kind+'-configs', f'isa = XCConfigurationList; buildConfigurations = {refs([kind+"-Debug",kind+"-Release"])}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
add('target', f'''isa = PBXNativeTarget; buildConfigurationList = {uid("target-configs")};
    buildPhases = {refs(["sources","frameworks","resources"])}; buildRules = (); dependencies = ();
    name = SimplePiano; productName = SimplePiano; productReference = {uid("product")}; productType = "com.apple.product-type.application";''')
add('project', f'''isa = PBXProject; attributes = {{ LastUpgradeCheck = 1600; }};
    buildConfigurationList = {uid("project-configs")}; compatibilityVersion = "Xcode 14.0";
    developmentRegion = he; knownRegions = (he, en, Base); mainGroup = {uid("root-group")};
    productRefGroup = {uid("products")}; projectDirPath = ""; projectRoot = ""; targets = {refs(["target"])};''')
project=root/'SimplePiano.xcodeproj'
project.mkdir(exist_ok=True)
(project/'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n'+ '\n'.join(objects)+'\n}; rootObject = '+uid('project')+'; }\n')
scheme=f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">
<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('target')}" BuildableName="SimplePiano.app" BlueprintName="SimplePiano" ReferencedContainer="container:SimplePiano.xcodeproj"/>
</BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES">
<BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('target')}" BuildableName="SimplePiano.app" BlueprintName="SimplePiano" ReferencedContainer="container:SimplePiano.xcodeproj"/></BuildableProductRunnable>
</LaunchAction><ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"/>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/></Scheme>
'''
path=project/'xcshareddata/xcschemes';path.mkdir(parents=True, exist_ok=True)
(path/'SimplePiano.xcscheme').write_text(scheme)
