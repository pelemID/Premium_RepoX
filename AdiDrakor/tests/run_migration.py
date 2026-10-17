#!/usr/bin/env python3
"""Compile each plugin in isolation and replay upstream H5 fixtures against every local port.
Moviebox test sources are read only and never packaged. No cross-module production dependencies.
"""
import os
from pathlib import Path
import runpy
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
# Reuse the audited SDK/test dependency setup and run the unchanged original regression suite.
env = runpy.run_path(str(ROOT / "Moviebox/tests/run_regression.py"))
MODULES = {"AdiDrakor": "com.AdiDrakor", "AdiFilmSemi": "com.michat88",
           "AdiXtream": "com.adixtream", "Adicinemax21": "com.Adicinemax21"}
for module, package in MODULES.items():
    source = sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))
    production = "\n".join(p.read_text() for p in source)
    for forbidden in ["invokeIdlix", "invokeVidSrc", "MovieBoxV2Shared", "PrefetchProvider", "PlaybackFixedProvider", "play-info"]:
        # Production comments may mention the retired endpoint; no quoted request path may use it.
        assert forbidden not in production if forbidden != "play-info" else 'subject-api/play-info' not in production
    assert production.count("@CloudstreamPlugin") == 1
    assert f"registerMainAPI({module}())" in production
    assert 'srcDir(' not in (ROOT / module / "build.gradle.kts").read_text()
    assert "com.Moviebox" not in production
    for other, other_package in MODULES.items():
        if other != module:
            assert f"import {other_package}." not in production
    expected=(ROOT / "Moviebox/src/main/kotlin/com/Moviebox/MovieboxPlayback.kt").read_text()
    actual=next(p for p in source if p.name == module+"MovieboxPlayback.kt").read_text()
    normalized=actual.replace(package,"com.Moviebox").replace(module+"MovieboxPlayback","MovieboxPlayback")
    # V4 changes only validation scheduling/reporting. Preserve byte equality of
    # parsing, audio, credential scope, headers, sample compatibility, HLS and DASH.
    start="    suspend fun validate(s: Stream, headers: Map<String, String>,"
    end="    private suspend fun binary"
    original_validation=expected[expected.index(start):expected.index(end)]
    normalized=normalized[:normalized.index(start)]+original_validation+normalized[normalized.index(end):]
    assert normalized==expected, "upstream stream/security/parser contract drift"
    upstream=(ROOT / "Moviebox/src/main/kotlin/com/Moviebox/MovieboxProvider.kt").read_text()
    port=next(p for p in source if p.name==module+"MovieboxEngine.kt").read_text()
    port=port.replace(module+"MovieboxPlayback","MovieboxPlayback")
    def section(text,first,last):return text[text.index(first):text.index(last)]
    # The actual interceptor remains exact; behavioral fixtures now cover the
    # deliberately different request-local media scheduler and early callbacks.
    original=section(upstream,"    override fun getVideoInterceptor(","    /** Do not log response bodies")
    assert section(port,"    fun getVideoInterceptor(","    /** Do not log response bodies")==original.replace("override fun","fun")
    assert section(port,"        fun webHeaders(","        suspend fun metadata(")==section(upstream,"        fun webHeaders(","        suspend fun metadata(")
    first="        // Each validated stream/resource ID owns its captions;"
    last='        if (choices.isEmpty())'
    assert section(port,first,last)==section(upstream,first,last)
    assert 'streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1' in port
    for suffix in ["Mapping", "Engine", "Playback", "Media", "Diagnostics"]:
        canonical=(ROOT / "AdiDrakor/src/main/kotlin/com/AdiDrakor" / ("AdiDrakorMoviebox"+suffix+".kt")).read_text()
        actual=next(p for p in source if p.name==module+"Moviebox"+suffix+".kt").read_text()
        actual=actual.replace(package,"com.AdiDrakor").replace(module,"AdiDrakor").replace(module.lower()+"_moviebox_identity","adidrakor_moviebox_identity")
        assert actual==canonical, "finder/interceptor/diagnostic parity drift"
    assert not (ROOT / "Adicinemax21/src/main/kotlin/com/lagradost/cloudstream3/utils").exists()
    with tempfile.TemporaryDirectory(prefix=f"{module}-h5-") as tmp:
        tmp = Path(tmp)
        env["compile_to"](tmp / "production.jar", source)
        tests = []
        for filename in ["PlaybackRegression.kt", "PlaybackIntegration.kt"]:
            text=(ROOT / "Moviebox/tests" / filename).read_text().replace("package com.Moviebox",f"package {package}")
            text=text.replace("MovieBoxProvider()",f'{module}MovieboxEngine("{module}")')
            text=text.replace("MovieBoxProvider",module+"MovieboxEngine").replace("MovieboxPlayback",module+"MovieboxPlayback")
            target=tmp / filename;target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/MappingRegression.kt.in").read_text()
        for key,value in {"PACKAGE":package,"MATCHER":module+"MovieboxMapping","PLAYBACK":module+"MovieboxPlayback","PROVIDER":module}.items():
            text=text.replace(key,value)
        target=tmp / "MappingRegression.kt";target.write_text(text);tests.append(target)
        text=(ROOT / "AdiDrakor/tests/MediaRecoveryRegression.kt.in").read_text()
        for key,value in {"PACKAGE":package,"V4MATCHER":module+"MovieboxMapping","V4PLAYBACK":module+"MovieboxPlayback","V4MEDIA":module+"MovieboxMedia","V4LOG":module+"MovieboxDiagnostics","V4ENGINE":module+"MovieboxEngine","V4PROVIDER":module}.items():
            text=text.replace(key,value)
        target=tmp / "MediaRecoveryRegression.kt";target.write_text(text);tests.append(target)
        tests += sorted((ROOT / "Moviebox/tests/shims").glob("*.kt"))
        # Compile fixtures separately against the actual production jar. Friend access only
        # exposes internal ports to tests; test shims precede the SDK at runtime.
        subprocess.run(["java","-cp",env["compiler_cp"],"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib","-no-reflect","-jvm-target","11","-Xfriend-paths="+str(tmp / "production.jar"),
            "-classpath",str(tmp / "production.jar")+os.pathsep+env["classpath"],
            "-d",str(tmp / "tests.jar"),*map(str,tests)],check=True)
        for entry in ["PlaybackRegressionKt","PlaybackIntegrationKt","MappingRegressionKt","MediaRecoveryRegressionKt"]:
            subprocess.run(["java","-Dmigration.fixtures="+str(ROOT / "AdiDrakor/tests/fixtures"),"-cp",str(tmp / "tests.jar")+os.pathsep+str(tmp / "production.jar")+os.pathsep+env["classpath"],package+"."+entry],check=True,timeout=150)
    print(f"PASS {module}: isolated SDK compile, original H5 parity, TMDB mapping and registered MainAPI",flush=True)
print("ALL FOUR H5 MIGRATIONS PASS; live service and Android device playback NOT_TESTED")
