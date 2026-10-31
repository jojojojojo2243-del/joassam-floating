/* 조아저씨쌈 통합 앱: 웹 화면(주문관리)과 안드로이드 앱을 잇는 코드. 페이지가 열릴 때마다 앱이 넣어 줌 (여러 번 넣어도 한 번만 동작) */
(function () {
  if (window.__joassamBridge) return;
  window.__joassamBridge = 1;
  var A = window.JoassamApp;
  if (!A) return;

  /* ---------- 1) 엑셀 등 파일 내려받기: 웹뷰는 blob 링크를 못 받으므로 내용을 앱으로 넘겨 '다운로드' 폴더에 저장 ---------- */
  function saveBlob(blob, name) {
    try {
      var r = new FileReader();
      r.onload = function () {
        var s = String(r.result || "");
        var i = s.indexOf(",");
        A.saveFile(String(name || "download"), String(blob.type || ""), i >= 0 ? s.slice(i + 1) : "");
      };
      r.onerror = function () { A.saveFailed(String(name || "")); };
      r.readAsDataURL(blob);
    } catch (e) { try { A.saveFailed(String(name || "")); } catch (e2) {} }
  }
  function grab(href, name) {
    fetch(href).then(function (res) { return res.blob(); }).then(function (b) { saveBlob(b, name); })
      .catch(function () { try { A.saveFailed(String(name || "")); } catch (e) {} });
  }
  function isLocal(h) { return /^(blob:|data:)/i.test(String(h || "")); }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      var h = this.href || this.getAttribute("href") || "";
      if (this.hasAttribute("download") && isLocal(h)) { grab(h, this.getAttribute("download") || "download"); return; }
    } catch (e) {}
    return origClick.apply(this, arguments);
  };
  document.addEventListener("click", function (e) {
    try {
      var a = e.target && e.target.closest ? e.target.closest("a[download]") : null;
      if (!a) return;
      var h = a.href || a.getAttribute("href") || "";
      if (!isLocal(h)) return;
      e.preventDefault();
      grab(h, a.getAttribute("download") || "download");
    } catch (err) {}
  }, true);

  /* ---------- 2) 음성 인식: 웹뷰에는 webkitSpeechRecognition 이 없어서 안드로이드 음성 인식으로 대신 ---------- */
  if (!(window.SpeechRecognition || window.webkitSpeechRecognition) && A.hasSpeech && A.hasSpeech()) {
    var all = {}, seq = 0;
    var SR = function () {
      this.lang = "ko-KR"; this.continuous = false; this.interimResults = false; this.maxAlternatives = 1;
      this.onresult = null; this.onerror = null; this.onend = null; this.onstart = null; this.onaudiostart = null;
      this.onsoundstart = null; this.onspeechstart = null; this.onspeechend = null; this.onnomatch = null;
      this._id = ++seq; this._ls = {}; this._finals = []; all[this._id] = this;
    };
    SR.prototype.addEventListener = function (t, f) { (this._ls[t] = this._ls[t] || []).push(f); };
    SR.prototype.removeEventListener = function (t, f) { var l = this._ls[t] || []; var i = l.indexOf(f); if (i >= 0) l.splice(i, 1); };
    SR.prototype._emit = function (t, ev) {
      ev = ev || {}; ev.type = t; ev.target = this;
      try { if (typeof this["on" + t] === "function") this["on" + t](ev); } catch (e) { setTimeout(function () { throw e; }); }
      var l = (this._ls[t] || []).slice();
      for (var i = 0; i < l.length; i++) { try { l[i](ev); } catch (e) { setTimeout(function () { throw e; }); } }
    };
    SR.prototype.start = function () {
      this._finals = [];
      var ok = A.speechStart(this._id, String(this.lang || "ko-KR"), !!this.continuous, !!this.interimResults, Number(this.maxAlternatives) || 1);
      if (ok === false) throw new Error("InvalidStateError");
    };
    SR.prototype.stop = function () { A.speechStop(this._id); };
    SR.prototype.abort = function () { A.speechAbort(this._id); };
    function makeResult(alts, isFinal) {
      var r = [];
      for (var i = 0; i < alts.length; i++) r.push({ transcript: String(alts[i].t || ""), confidence: Number(alts[i].c) || 0 });
      if (!r.length) r.push({ transcript: "", confidence: 0 });
      r.isFinal = !!isFinal;
      r.item = function (k) { return this[k]; };
      return r;
    }
    /* 앱이 부름: __joassamSpeech(번호, 종류, 내용) */
    window.__joassamSpeech = function (id, kind, data) {
      var rec = all[id];
      if (!rec) return;
      if (kind === "partial" || kind === "final") {
        var alts = (data && data.alts) || [];
        var list = rec._finals.slice();
        var idx;
        if (kind === "final") { rec._finals.push(makeResult(alts, true)); list = rec._finals.slice(); idx = list.length - 1; }
        else { if (!rec.interimResults) return; list.push(makeResult(alts, false)); idx = list.length - 1; }
        list.item = function (k) { return this[k]; };
        rec._emit("result", { results: list, resultIndex: idx });
      } else if (kind === "error") {
        rec._emit("error", { error: String((data && data.error) || "aborted"), message: "" });
      } else if (kind === "end") {
        rec._emit("end", {});
      } else {
        rec._emit(kind, {});
      }
    };
    window.SpeechRecognition = SR;
    window.webkitSpeechRecognition = SR;
  }
})();
