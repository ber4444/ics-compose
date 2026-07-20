package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window

@JsFun("""
function initPreviewEngine() {
    window.previewEngine = {
        db: null,
        hls: null,
        video: null,
        canvas: null,
        ctx: null,
        currentEvent: null,
        resolves: [],
        initDb: function() {
            var req = window.indexedDB.open("ics_previews", 1);
            req.onupgradeneeded = function(e) {
                var db = e.target.result;
                if (!db.objectStoreNames.contains("frames")) {
                    db.createObjectStore("frames", { keyPath: "id" });
                }
            };
            req.onsuccess = function(e) {
                window.previewEngine.db = e.target.result;
            };
        },
        getFrame: function(eventNumber, url, timestamp, callback) {
            var frameId = eventNumber + "_" + Math.floor(timestamp);
            
            // Check cache
            if (window.previewEngine.db) {
                var tx = window.previewEngine.db.transaction("frames", "readonly");
                var store = tx.objectStore("frames");
                var req = store.get(frameId);
                req.onsuccess = function(e) {
                    if (req.result) {
                        callback(req.result.dataUrl);
                        return;
                    }
                    window.previewEngine.extract(eventNumber, url, timestamp, frameId, callback);
                };
                req.onerror = function() {
                    window.previewEngine.extract(eventNumber, url, timestamp, frameId, callback);
                };
            } else {
                window.previewEngine.extract(eventNumber, url, timestamp, frameId, callback);
            }
        },
        extract: function(eventNumber, url, timestamp, frameId, callback) {
            var engine = window.previewEngine;
            if (!engine.video) {
                engine.video = document.createElement("video");
                engine.video.muted = true;
                engine.video.crossOrigin = "anonymous";
                engine.canvas = document.createElement("canvas");
                engine.canvas.width = 284;
                engine.canvas.height = 160;
                engine.ctx = engine.canvas.getContext("2d");
                
                engine.video.addEventListener('seeked', function() {
                    if (engine.resolves.length > 0) {
                        var cb = engine.resolves.shift();
                        engine.ctx.drawImage(engine.video, 0, 0, engine.canvas.width, engine.canvas.height);
                        var dataUrl = engine.canvas.toDataURL("image/jpeg", 0.5);
                        
                        // Cache it
                        if (engine.db) {
                            var tx = engine.db.transaction("frames", "readwrite");
                            tx.objectStore("frames").put({ id: engine.currentFrameId, dataUrl: dataUrl });
                        }
                        cb(dataUrl);
                    }
                });
            }
            
            if (engine.currentEvent !== eventNumber) {
                if (engine.hls) engine.hls.destroy();
                engine.currentEvent = eventNumber;
                if (window.Hls && window.Hls.isSupported()) {
                    engine.hls = new window.Hls();
                    engine.hls.loadSource(url);
                    engine.hls.attachMedia(engine.video);
                } else {
                    engine.video.src = url;
                }
            }
            
            engine.currentFrameId = frameId;
            engine.resolves.push(callback);
            engine.video.currentTime = timestamp;
        }
    };
    window.previewEngine.initDb();
}
""")
internal external fun initPreviewEngine()

@JsFun("""
function requestFrame(eventNumber, url, timestamp, callback) {
    if (window.previewEngine) {
        window.previewEngine.getFrame(eventNumber, url, timestamp, callback);
    }
}
""")
internal external fun requestFrame(eventNumber: Int, url: String, timestamp: Double, callback: (String) -> Unit)
