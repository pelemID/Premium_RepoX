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
    assert actual.replace(package,"com.Moviebox").replace(module+"MovieboxPlayback","MovieboxPlayback") == expected
    upstream=(ROOT / "Moviebox/src/main/kotlin/com/Moviebox/MovieboxProvider.kt").read_text()
    upstream_flow=upstream[upstream.index("    override fun getVideoInterceptor("):upstream.index("    data class RankingResponse(")]
    port=next(p for p in source if p.name == module+"MovieboxEngine.kt").read_text()
    port=port.replace(module+"MovieboxPlayback","MovieboxPlayback")
    port_flow=port[port.index("    fun getVideoInterceptor("):port.index("    suspend fun resolve(")]
    upstream_flow=upstream_flow.replace("override fun","fun").replace("override suspend fun","suspend fun")
    # Standalone Moviebox now recovers navigation context; the Adi engines already
    # receive verified context through their own independent TMDB adapters.
    context_hook='''            val path = playbackContext.knownPath(detail,subject).orEmpty().ifBlank {
                playbackContext.resolve(subject,selected.subjectId,selected.title,selected.detailPath,detail) { url ->
                    playbackRequest(url,mapOf("User-Agent" to MovieboxPlayback.WEB_UA,"Referer" to "https://moviebox.ph/"))
                        ?.takeIf { it.first==200 }?.second
                }
            }'''
    assert upstream_flow.count(context_hook)==1, "unexpected standalone context hook"
    upstream_flow=upstream_flow.replace(context_hook,'''            val path = MovieboxPlayback.detailPath(detail,subject)''')
    port_flow=port_flow.replace(',\n        metadataContext: Map<String,JSONObject> = emptyMap(),\n        onStage: (String) -> Unit = {}', '')
    port_flow=port_flow.replace('            onStage(if(candidates.isEmpty()) "H5_NO_STREAM" else if(count==0) "H5_MEDIA_INVALID" else "PLAYBACK_SUCCESS")\n','')
    port_flow=port_flow.replace('            metadataContext[subject]?.takeIf { MovieboxPlayback.text(it,"subjectId")==subject }?.let { return it }\n','')
    assert port_flow.strip() == upstream_flow.strip(), "H5 flow/interceptor drift"
    for suffix in ["Mapping", "Engine"]:
        canonical=(ROOT / "AdiDrakor/src/main/kotlin/com/AdiDrakor" / ("AdiDrakorMoviebox"+suffix+".kt")).read_text()
        actual=next(p for p in source if p.name==module+"Moviebox"+suffix+".kt").read_text()
        actual=actual.replace(package,"com.AdiDrakor").replace(module+"Moviebox","AdiDrakorMoviebox").replace(module.lower()+"_moviebox_identity","adidrakor_moviebox_identity")
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
        tests += sorted((ROOT / "Moviebox/tests/shims").glob("*.kt"))
        # Compile fixtures separately against the actual production jar. Friend access only
        # exposes internal ports to tests; test shims precede the SDK at runtime.
        subprocess.run(["java","-cp",env["compiler_cp"],"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib","-no-reflect","-jvm-target","11","-Xfriend-paths="+str(tmp / "production.jar"),
            "-classpath",str(tmp / "production.jar")+os.pathsep+env["classpath"],
            "-d",str(tmp / "tests.jar"),*map(str,tests)],check=True)
        for entry in ["PlaybackRegressionKt","PlaybackIntegrationKt","MappingRegressionKt"]:
            subprocess.run(["java","-Dmigration.fixtures="+str(ROOT / "AdiDrakor/tests/fixtures"),"-cp",str(tmp / "tests.jar")+os.pathsep+str(tmp / "production.jar")+os.pathsep+env["classpath"],package+"."+entry],check=True,timeout=150)
    print(f"PASS {module}: isolated SDK compile, original H5 parity, TMDB mapping and registered MainAPI",flush=True)
print("ALL FOUR H5 MIGRATIONS PASS; live service and Android device playback NOT_TESTED")
