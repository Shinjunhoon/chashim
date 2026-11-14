/*
 * 제주 하늘 레이스 (차쉼 엔터테인 모드)
 * 하늘에 떠 있는 바람길을 따라 날며 새벽 바다 → 현무암 해안 → 오름과 감귤밭 → 한라산 구름 → 성산일출봉 해돋이까지 달린다.
 * 입력: 앱이 race.input(steer, brake)로 핸들(-1~1, 오른쪽 +)과 브레이크를 넘겨준다.
 * 출력: Android.event(JSON)으로 바람 세기, 가속, 별빛, 먹구름, 구간, 결승을 알려 준다. 차의 4D 효과는 앱이 맡는다.
 */
(function () {
  'use strict';

  // ---------- 설정 ----------
  const L = 6400;               // 코스 길이
  const HALF_W = 9;             // 바람길 반폭
  const V_MIN = 34, V_MAX = 78; // 바람길 밖 / 안 최고 속도
  const BOOST = 40;             // 순풍 고리 가속
  const DIVE = -12;             // 브레이크로 내려갈 수 있는 깊이
  const STEER_RATE = 36;        // 핸들 끝까지 꺾었을 때 옆으로 움직이는 속도
  const Q = new URLSearchParams(location.search);
  const DEBUG = Q.has('debug');
  if (Q.has('nooverlay')) ['vignette', 'flash', 'center'].forEach((id) => { const e = document.getElementById(id); if (e) e.style.display = 'none'; });

  const ZONES = [
    { p: 0.00, id: 'sea',     title: '새벽 바다',        caption: '빛나는 바람길을 따라 날아요', ambient: 0x3A5BD0, name: '새벽 남색', dir: 'FACE', recirc: false },
    { p: 0.17, id: 'coast',   title: '현무암 해안',      caption: '창문으로 바닷바람이 들어와요', ambient: 0x2FC4C4, name: '바다 청록', window: 10, recirc: false },
    { p: 0.38, id: 'oreum',   title: '오름과 감귤밭',    caption: '오름 사이로 바람길이 굽이쳐요', ambient: 0x8CCB4A, name: '오름 연두', window: 0 },
    { p: 0.58, id: 'halla',   title: '한라산',          caption: '구름 속 먹구름은 브레이크로 아래로 피하세요', ambient: 0xC8DCFF, name: '구름 흰빛', wiper: 3, dir: 'FACE_AND_DEFROST', vent: 2, recirc: true },
    { p: 0.80, id: 'sunrise', title: '성산일출봉 해돋이', caption: '햇살이 손에 닿아요. 결승까지!', ambient: 0xFF9A3C, name: '일출 주황', seat: 1, wheel: 1, vent: 0, dir: 'FACE_AND_FLOOR' },
  ];

  // 시간 흐름에 따른 하늘 (새벽 → 아침 → 낮 구름 → 해돋이)
  const SKY = [
    { p: 0.00, top: 0x0a1a3a, bottom: 0x3b5a94, fog: 0x2c4778, sun: 0x8fa8ff, sunI: 0.35, hemi: 0.45, el: 0.25 },
    { p: 0.20, top: 0x1b3f7a, bottom: 0x8fb4e0, fog: 0x7d9cc8, sun: 0xffe2c0, sunI: 0.8, hemi: 0.6, el: 0.35 },
    { p: 0.45, top: 0x2f78c4, bottom: 0xbfe0ff, fog: 0xa9cdee, sun: 0xffffff, sunI: 1.15, hemi: 0.75, el: 0.6 },
    { p: 0.66, top: 0x6b9fd4, bottom: 0xf0f6ff, fog: 0xe8f0fa, sun: 0xffffff, sunI: 1.0, hemi: 0.85, el: 0.7 },
    { p: 0.80, top: 0x34407a, bottom: 0xff9f6a, fog: 0xd99a7a, sun: 0xffb070, sunI: 1.0, hemi: 0.55, el: 0.08 },
    { p: 1.00, top: 0x3d4f95, bottom: 0xffc27a, fog: 0xf0b48a, sun: 0xffc890, sunI: 1.25, hemi: 0.65, el: 0.12 },
  ];

  // ---------- 도우미 ----------
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
  function hash(n) { const s = Math.sin(n * 127.1) * 43758.5453; return s - Math.floor(s); }
  function noise2(x, y) {
    const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi;
    const h = (a, b) => hash(a * 57.0 + b * 113.0);
    const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
    return lerp(lerp(h(xi, yi), h(xi + 1, yi), u), lerp(h(xi, yi + 1), h(xi + 1, yi + 1), u), v);
  }
  function fbm(x, y) { return noise2(x, y) * 0.55 + noise2(x * 2.1, y * 2.1) * 0.3 + noise2(x * 4.3, y * 4.3) * 0.15; }
  const colorLerp = (a, b, t) => new THREE.Color(a).lerp(new THREE.Color(b), t);
  function skyAt(p) {
    let i = 0; while (i < SKY.length - 2 && p > SKY[i + 1].p) i++;
    const a = SKY[i], b = SKY[i + 1], t = smooth(a.p, b.p, p);
    return {
      top: colorLerp(a.top, b.top, t), bottom: colorLerp(a.bottom, b.bottom, t), fog: colorLerp(a.fog, b.fog, t),
      sun: colorLerp(a.sun, b.sun, t), sunI: lerp(a.sunI, b.sunI, t), hemi: lerp(a.hemi, b.hemi, t), el: lerp(a.el, b.el, t),
    };
  }
  function send(o) { try { window.Android && Android.event(JSON.stringify(o)); } catch (e) {} }

  // ---------- 코스 (바람길) ----------
  function trackX(p) { return 110 * Math.sin(p * Math.PI * 2 * 2.3) + 50 * Math.sin(p * Math.PI * 2 * 5.1 + 1.0); }
  function trackY(p) {
    const climb = 150 * Math.exp(-Math.pow((p - 0.69) / 0.085, 2));   // 한라산 어깨를 감아 오른다
    return 30 + 7 * Math.sin(p * Math.PI * 2 * 7) + climb - 10 * smooth(0.85, 1, p);
  }
  const N = 1600;
  const pts = [], tans = [], sides = [];
  for (let i = 0; i <= N; i++) { const p = i / N; pts.push(new THREE.Vector3(trackX(p), trackY(p), -p * L)); }
  const UP = new THREE.Vector3(0, 1, 0);
  for (let i = 0; i <= N; i++) {
    const a = pts[Math.max(0, i - 1)], b = pts[Math.min(N, i + 1)];
    const t = b.clone().sub(a).normalize(); tans.push(t);
    sides.push(new THREE.Vector3().crossVectors(t, UP).normalize());
  }
  function sample(arr, s) {
    const f = clamp(s / L, 0, 1) * N, i = Math.min(N - 1, Math.floor(f)), u = f - i;
    return arr[i].clone().lerp(arr[i + 1], u);
  }
  // 옆으로 휘는 정도 (단위 거리당 방향 변화, 오른쪽 굽음 +)
  function curvature(s) {
    const t1 = sample(tans, s), t2 = sample(tans, s + 6);
    const a1 = Math.atan2(t1.x, -t1.z), a2 = Math.atan2(t2.x, -t2.z);
    let d = a2 - a1; if (d > Math.PI) d -= 2 * Math.PI; if (d < -Math.PI) d += 2 * Math.PI;
    return d / 6;
  }

  // ---------- 장면 ----------
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.25));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.outputEncoding = THREE.sRGBEncoding;
  Object.assign(renderer.domElement.style, { position: 'fixed', left: '0', top: '0', zIndex: '0' });
  document.body.appendChild(renderer.domElement);
  if (DEBUG) {
    const gl = renderer.getContext(), ext = gl.getExtension('WEBGL_debug_renderer_info');
    console.log('webgl ' + (renderer.capabilities.isWebGL2 ? '2' : '1') + ' ' + (ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER)) + ' size ' + window.innerWidth + 'x' + window.innerHeight + ' dpr ' + window.devicePixelRatio);
  }
  const scene = new THREE.Scene();
  scene.fog = new THREE.Fog(0x2c4778, 120, 1100);
  const camera = new THREE.PerspectiveCamera(66, window.innerWidth / window.innerHeight, 0.5, 3200);
  window.addEventListener('resize', () => {
    renderer.setSize(window.innerWidth, window.innerHeight);
    camera.aspect = window.innerWidth / window.innerHeight; camera.updateProjectionMatrix();
  });

  const hemi = new THREE.HemisphereLight(0xbfd8ff, 0x2a3c46, 0.6); scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffffff, 1); scene.add(sun); scene.add(sun.target);

  // 하늘: 위아래 그러데이션 + 해 빛무리
  const skyMat = new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false, fog: false,
    uniforms: { top: { value: new THREE.Color() }, bottom: { value: new THREE.Color() }, sunDir: { value: new THREE.Vector3() }, sunCol: { value: new THREE.Color() } },
    vertexShader: 'varying vec3 vDir; void main(){ vDir = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
    fragmentShader: `uniform vec3 top; uniform vec3 bottom; uniform vec3 sunDir; uniform vec3 sunCol; varying vec3 vDir;
      void main(){ float h = clamp(vDir.y*1.6+0.15, 0.0, 1.0); vec3 c = mix(bottom, top, pow(h, 0.8));
        float s = max(dot(normalize(vDir), normalize(sunDir)), 0.0);
        c += sunCol * (pow(s, 600.0)*1.6 + pow(s, 24.0)*0.35 + pow(s, 4.0)*0.12);
        gl_FragColor = vec4(c, 1.0); }`,
  });
  const sky = new THREE.Mesh(new THREE.SphereGeometry(2800, 32, 16), skyMat); scene.add(sky);

  // 밤하늘 별 (새벽이 밝아지면 사라진다)
  const starGeo = new THREE.BufferGeometry(); const sp = [];
  for (let i = 0; i < 700; i++) {
    const th = hash(i) * Math.PI * 2, ph = 0.08 + hash(i + 999) * 1.2;
    sp.push(Math.cos(th) * Math.cos(ph) * 2500, Math.sin(ph) * 2500, Math.sin(th) * Math.cos(ph) * 2500);
  }
  starGeo.setAttribute('position', new THREE.Float32BufferAttribute(sp, 3));
  const nightStars = new THREE.Points(starGeo, new THREE.PointsMaterial({ color: 0xffffff, size: 2.2, sizeAttenuation: false, transparent: true, fog: false }));
  scene.add(nightStars);

  // 바다: 카메라를 따라다니는 촘촘한 물결 면 (세계 좌표 기준 물결이라 움직여도 미끄러지지 않는다)
  const seaUniforms = { uTime: { value: 0 } };
  const seaMat = new THREE.MeshStandardMaterial({ color: 0x1b4f78, roughness: 0.62, metalness: 0.0, flatShading: true });
  seaMat.onBeforeCompile = (sh) => {
    sh.uniforms.uTime = seaUniforms.uTime;
    sh.vertexShader = 'uniform float uTime;\n' + sh.vertexShader.replace('#include <begin_vertex>',
      `#include <begin_vertex>
       vec4 wp = modelMatrix * vec4(position, 1.0);
       transformed.z += sin(wp.x*0.06 + uTime*0.9)*0.55 + cos(wp.z*0.075 + uTime*1.15)*0.5 + sin((wp.x-wp.z)*0.15 + uTime*1.8)*0.2;`);
  };
  const SEA_SIZE = 2600, SEA_SEG = 170, SEA_CELL = SEA_SIZE / SEA_SEG;
  const sea = new THREE.Mesh(new THREE.PlaneGeometry(SEA_SIZE, SEA_SIZE, SEA_SEG, SEA_SEG), seaMat);
  sea.rotation.x = -Math.PI / 2; scene.add(sea);
  // 아주 먼 바다 (물결 없음)
  const farSea = new THREE.Mesh(new THREE.PlaneGeometry(9000, L * 2), new THREE.MeshStandardMaterial({ color: 0x1b4f78, roughness: 0.6 }));
  farSea.rotation.x = -Math.PI / 2; farSea.position.set(0, -0.6, -L / 2); scene.add(farSea);

  // ---------- 땅 (제주) ----------
  const HALLA = { p: 0.70, dx: 390, h: 400, r: 330 };
  const hallaX = trackX(HALLA.p) + HALLA.dx, hallaZ = -HALLA.p * L;
  const SEONG = { x: trackX(1) + 10, z: -L * 1.045, r: 150, h: 120 };
  const oreums = [];
  for (let i = 0; i < 26; i++) {
    const p = 0.36 + hash(i + 3) * 0.28;
    const side = hash(i + 50) > 0.5 ? 1 : -1;
    const off = 70 + hash(i + 77) * 380;
    oreums.push({ x: trackX(p) + side * off, z: -p * L, r: 45 + hash(i + 11) * 45, h: 35 + hash(i + 13) * 45 });
  }
  function landMask(x, z) {
    const p = -z / L;
    const tx = trackX(clamp(p, 0, 1));
    const shoreIn = lerp(-120, 700, smooth(0.16, 0.42, p));     // 해안: 왼쪽부터 땅이 넓어진다
    const shoreOut = lerp(700, -260, smooth(0.80, 0.92, p));    // 끝: 땅이 왼쪽으로 물러나 바다가 된다
    const edge = Math.min(shoreIn, shoreOut) + (fbm(x * 0.008, z * 0.008) - 0.5) * 120;
    return smooth(-30, 30, edge - (x - tx) * -1 + 0) * (p > 0.12 ? 1 : 0);
  }
  function landH(x, z) {
    const m = landMask(x, z);
    let h = -9 + 27 * smooth(0.3, 0.55, m);   // 현무암 절벽
    if (m > 0.3) h += fbm(x * 0.01, z * 0.01) * 14 * m;
    for (const o of oreums) {
      const d = Math.hypot(x - o.x, z - o.z);
      if (d < o.r * 1.6) { const k = Math.max(0, 1 - d / (o.r * 1.6)); let oh = o.h * Math.pow(k, 1.6); if (d < o.r * 0.35) oh -= o.h * 0.25 * (1 - d / (o.r * 0.35)); h = Math.max(h, oh + 4); }
    }
    const dh = Math.hypot(x - hallaX, z - hallaZ);
    h = Math.max(h, HALLA.h * Math.exp(-Math.pow(dh / HALLA.r, 2)) + (fbm(x * 0.02, z * 0.02) - 0.5) * 18 * Math.exp(-Math.pow(dh / 500, 2)));
    const ds = Math.hypot(x - SEONG.x, z - SEONG.z);
    if (ds < SEONG.r * 1.3) {
      let sh = SEONG.h * smooth(SEONG.r * 1.3, SEONG.r * 0.75, ds);
      if (ds < SEONG.r * 0.6) sh -= SEONG.h * 0.55 * smooth(SEONG.r * 0.6, SEONG.r * 0.25, ds);
      sh += (fbm(x * 0.05, z * 0.05) - 0.5) * 10;
      h = Math.max(h, sh - 9);
    }
    // 바람길 아래는 계곡처럼 깎아서 항상 넉넉히 떠 있게 한다
    const tp = clamp(-z / L, 0, 1), dxT = Math.abs(x - trackX(tp)), cap = trackY(tp) - 26;
    if (dxT < 110 && h > cap) h = lerp(h, cap, smooth(110, 45, dxT));
    return h;
  }
  const terrGeo = new THREE.PlaneGeometry(2400, L * 1.2, 140, 440);
  terrGeo.rotateX(-Math.PI / 2); terrGeo.translate(0, 0, -L * 0.55);
  {
    const pos = terrGeo.attributes.position, col = [];
    const cSand = new THREE.Color(0xc9b98f), cGrass = new THREE.Color(0x4f7d3a), cGrass2 = new THREE.Color(0x6f9a42),
      cRock = new THREE.Color(0x3a3a40), cYellow = new THREE.Color(0xf2d23a), cHigh = new THREE.Color(0x6d7a63), cSeabed = new THREE.Color(0x1d3d52);
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i), z = pos.getZ(i), h = landH(x, z); pos.setY(i, h);
      const p = -z / L; let c;
      if (h < -1) c = cSeabed;
      else if (h < 2.5 && p > 0.15) c = cSand;
      else {
        const slope = Math.abs(landH(x + 4, z) - h) + Math.abs(landH(x, z + 4) - h);
        c = (slope > 7 ? cRock : (fbm(x * 0.02, z * 0.02) > 0.5 ? cGrass : cGrass2)).clone();
        if (p > 0.17 && p < 0.4 && slope < 4 && fbm(x * 0.012 + 5, z * 0.012) > 0.58) c = cYellow.clone();   // 유채꽃
        if (h > 160) c.lerp(cHigh, smooth(160, 300, h));
      }
      col.push(c.r, c.g, c.b);
    }
    terrGeo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    terrGeo.computeVertexNormals();
  }
  scene.add(new THREE.Mesh(terrGeo, new THREE.MeshStandardMaterial({ vertexColors: true, flatShading: true, roughness: 0.95 })));

  // 감귤나무
  {
    const crowns = [], fruits = [];
    for (let i = 0; i < 900 && crowns.length < 320; i++) {
      const p = 0.38 + hash(i * 3.1) * 0.2, x = trackX(p) + (hash(i * 7.7) - 0.5) * 700, z = -p * L;
      if (Math.abs(x - trackX(p)) < 25) continue;
      const h = landH(x, z); if (h < 4 || h > 60) continue;
      crowns.push([x, h + 3, z, 2.4 + hash(i) * 1.2]);
      for (let k = 0; k < 4; k++) fruits.push([x + (hash(i + k) - 0.5) * 4, h + 2.5 + hash(i + k * 2) * 2.5, z + (hash(i + k * 3) - 0.5) * 4]);
    }
    const cm = new THREE.InstancedMesh(new THREE.IcosahedronGeometry(1, 0), new THREE.MeshStandardMaterial({ color: 0x2f5e2a, flatShading: true }), crowns.length);
    const fm = new THREE.InstancedMesh(new THREE.IcosahedronGeometry(0.55, 0), new THREE.MeshStandardMaterial({ color: 0xff8a1e, emissive: 0x552200, flatShading: true }), fruits.length);
    const m = new THREE.Matrix4();
    crowns.forEach((c, i) => { m.makeScale(c[3], c[3] * 0.85, c[3]); m.setPosition(c[0], c[1], c[2]); cm.setMatrixAt(i, m); });
    fruits.forEach((f, i) => { m.makeTranslation(f[0], f[1], f[2]); fm.setMatrixAt(i, m); });
    scene.add(cm, fm);
  }

  // 풍력 발전기 (해안)
  const blades = [];
  {
    const white = new THREE.MeshStandardMaterial({ color: 0xf2f4f6, flatShading: true });
    for (let i = 0; i < 9; i++) {
      const p = 0.2 + i * 0.022, x = trackX(p) - 90 - hash(i) * 160, z = -p * L, h = landH(x, z);
      if (h < 3) continue;
      const g = new THREE.Group(); g.position.set(x, h, z);
      const tower = new THREE.Mesh(new THREE.CylinderGeometry(0.7, 1.3, 46, 8), white); tower.position.y = 23; g.add(tower);
      const hub = new THREE.Group(); hub.position.set(0, 46, 1.2); g.add(hub);
      for (let k = 0; k < 3; k++) {
        const b = new THREE.Mesh(new THREE.BoxGeometry(1.2, 20, 0.3), white); b.position.y = 10;
        const arm = new THREE.Group(); arm.rotation.z = k * Math.PI * 2 / 3; arm.add(b); hub.add(arm);
      }
      g.rotation.y = 0.4; scene.add(g); blades.push(hub);
    }
  }

  // 현무암 바위섬과 돌하르방 (바다 구간)
  {
    const basalt = new THREE.MeshStandardMaterial({ color: 0x3e3e44, flatShading: true, roughness: 1 });
    for (let i = 0; i < 16; i++) {
      const p = 0.015 + i * 0.009, side = i % 2 ? 1 : -1;
      const x = trackX(p) + side * (55 + hash(i * 3.7) * 170), z = -p * L - hash(i) * 30;
      const r = 10 + hash(i * 1.9) * 16;
      const rock = new THREE.Mesh(new THREE.DodecahedronGeometry(r, 0), basalt);
      rock.position.set(x, -r * 0.35, z); rock.scale.set(1.3, 0.75 + hash(i * 5.1) * 0.5, 1.1); rock.rotation.y = hash(i) * 3;
      scene.add(rock);
      if (i % 3 === 1) {
        const g = new THREE.Group(); const top = r * (0.75 + hash(i * 5.1) * 0.5) * 0.62;
        const body = new THREE.Mesh(new THREE.CylinderGeometry(1.6, 2.0, 6, 7), basalt); body.position.y = 3;
        const head = new THREE.Mesh(new THREE.SphereGeometry(1.8, 7, 5), basalt); head.position.y = 7.3; head.scale.y = 1.15;
        const hat = new THREE.Mesh(new THREE.CylinderGeometry(1.5, 1.9, 1.6, 7), basalt); hat.position.y = 9.4;
        const nose = new THREE.Mesh(new THREE.SphereGeometry(0.55, 5, 4), basalt); nose.position.set(0, 7.0, 1.75);
        g.add(body, head, hat, nose); g.scale.setScalar(1.5); g.position.set(x, top, z); g.lookAt(trackX(p), top, z); scene.add(g);
      }
    }
  }

  // 구름: 한라산 구름층 + 곳곳의 뭉게구름
  {
    const puffs = [];
    for (let i = 0; i < 260; i++) {
      const p = 0.6 + hash(i * 1.7) * 0.2;
      const side = hash(i * 5.3) > 0.5 ? 1 : -1, r = 9 + hash(i * 2.2) * 14;
      const x = trackX(p) + side * (34 + r * 1.6 + hash(i * 6.7) * 240), z = -p * L, y = trackY(p) - 18 + (hash(i * 8.1) - 0.5) * 46;
      for (let k = 0; k < 5; k++) puffs.push([x + (hash(i + k) - 0.5) * r * 2.2, y + (hash(i + k * 5) - 0.3) * r * 0.6, z + (hash(i + k * 9) - 0.5) * r * 2, r * (0.6 + hash(i + k * 13) * 0.5)]);
    }
    for (let i = 0; i < 70; i++) {
      const p = hash(i * 9.1), x = trackX(p) + (hash(i * 3.3) - 0.5) * 1400, z = -p * L, y = 120 + hash(i) * 120;
      if (Math.abs(x - trackX(p)) < 60) continue;
      for (let k = 0; k < 5; k++) puffs.push([x + (hash(i + k * 3) - 0.5) * 40, y + hash(i + k) * 8, z + (hash(i + k * 7) - 0.5) * 30, 12 + hash(i + k * 11) * 10]);
    }
    const cloudMesh = new THREE.InstancedMesh(new THREE.IcosahedronGeometry(1, 1), new THREE.MeshStandardMaterial({ color: 0xffffff, flatShading: true, roughness: 1, emissive: 0xc9d6e6, emissiveIntensity: 0.45 }), puffs.length);
    const m = new THREE.Matrix4();
    puffs.forEach((c, i) => { m.makeScale(c[3], c[3] * 0.62, c[3]); m.setPosition(c[0], c[1], c[2]); cloudMesh.setMatrixAt(i, m); });
    scene.add(cloudMesh);
  }

  // ---------- 바람길 (레이싱 길) ----------
  const ribbonMat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, side: THREE.DoubleSide,
    uniforms: { uTime: { value: 0 }, uGlow: { value: 0 } },
    vertexShader: `varying vec2 vUv; varying float vDist;
      void main(){ vUv = uv; vec4 mv = modelViewMatrix * vec4(position,1.0); vDist = -mv.z; gl_Position = projectionMatrix * mv; }`,
    fragmentShader: `uniform float uTime; uniform float uGlow; varying vec2 vUv; varying float vDist;
      void main(){
        float e = abs(vUv.x - 0.5) * 2.0;
        float edge = smoothstep(0.78, 0.97, e) * (1.0 - smoothstep(0.97, 1.0, e));
        float s = fract(vUv.y * 0.06 - uTime * 1.6);
        float chevron = smoothstep(0.0, 0.04, s - e*0.06) * (1.0 - smoothstep(0.04, 0.12, s - e*0.06));
        vec3 cyan = vec3(0.55, 0.92, 1.0);
        vec3 col = mix(cyan, vec3(1.0), edge*0.6 + chevron*0.5);
        float a = 0.16 + edge * 0.75 + chevron * 0.35 * (1.0 - e) + uGlow*0.08;
        a *= 1.0 - smoothstep(380.0, 900.0, vDist);
        a *= smoothstep(2.0, 10.0, vDist);
        gl_FragColor = vec4(col, a);
      }`,
  });
  {
    const pos = [], uv = [], idx = [];
    let dist = 0;
    for (let i = 0; i <= N; i++) {
      if (i > 0) dist += pts[i].distanceTo(pts[i - 1]);
      const l = pts[i].clone().addScaledVector(sides[i], -HALF_W), r = pts[i].clone().addScaledVector(sides[i], HALF_W);
      pos.push(l.x, l.y, l.z, r.x, r.y, r.z); uv.push(0, dist, 1, dist);
      if (i < N) { const a = i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setIndex(idx);
    const ribbon = new THREE.Mesh(g, ribbonMat); ribbon.renderOrder = 2; scene.add(ribbon);
  }
  // 길 가장자리 불빛
  {
    const lamps = []; for (let s = 0; s < L; s += 34) for (const side of [-1, 1]) lamps.push(sample(pts, s).addScaledVector(sample(sides, s), side * (HALF_W + 0.6)));
    const lm = new THREE.InstancedMesh(new THREE.SphereGeometry(0.35, 6, 4), new THREE.MeshBasicMaterial({ color: 0xdffaff }), lamps.length);
    const m = new THREE.Matrix4(); lamps.forEach((p, i) => { m.makeTranslation(p.x, p.y, p.z); lm.setMatrixAt(i, m); });
    scene.add(lm);
  }
  // 속도감: 길 주변에 떠 있는 가는 바람 줄기
  {
    const n = 1400, m = new THREE.Matrix4(), q = new THREE.Quaternion(), sc = new THREE.Vector3(1, 1, 1);
    const streaks = new THREE.InstancedMesh(new THREE.BoxGeometry(0.06, 0.06, 5), new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.35 }), n);
    for (let i = 0; i < n; i++) {
      const s = hash(i * 1.37) * L, c = sample(pts, s), t = sample(tans, s), sd = sample(sides, s);
      const ang = hash(i * 2.1) * Math.PI * 2, rad = 12 + hash(i * 4.3) * 30;
      c.addScaledVector(sd, Math.cos(ang) * rad).addScaledVector(UP, Math.sin(ang) * rad * 0.6);
      q.setFromUnitVectors(new THREE.Vector3(0, 0, 1), t); m.compose(c, q, sc); streaks.setMatrixAt(i, m);
    }
    scene.add(streaks);
  }

  // 결승 문
  {
    const s = L * 0.985, c = sample(pts, s), sd = sample(sides, s), t = sample(tans, s);
    const mat = new THREE.MeshBasicMaterial({ color: 0xfff2c4 });
    const g = new THREE.Group(); g.position.copy(c);
    g.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, -1), t);
    const arch = new THREE.Mesh(new THREE.TorusGeometry(HALF_W + 3, 0.5, 8, 48, Math.PI), mat); g.add(arch);
    scene.add(g);
  }

  // ---------- 아이템: 별빛, 순풍 고리, 먹구름 ----------
  const glowTex = (() => {
    const c = document.createElement('canvas'); c.width = c.height = 64; const x = c.getContext('2d');
    const gr = x.createRadialGradient(32, 32, 0, 32, 32, 32); gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.3, 'rgba(255,240,200,.55)'); gr.addColorStop(1, 'rgba(255,220,150,0)');
    x.fillStyle = gr; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(c);
  })();
  const items = [];
  function at(s, x, y) { return sample(pts, s).addScaledVector(sample(sides, s), x).addScaledVector(UP, y); }
  // 별빛: 길을 따라 줄지어, 일부는 아래쪽(브레이크로 받기)
  const starDefs = [];
  for (let k = 0; k < 9; k++) {
    const s0 = L * (0.04 + k * 0.105), lane = (hash(k * 3.3) - 0.5) * 10, low = k % 3 === 2;
    for (let j = 0; j < 5; j++) starDefs.push({ s: s0 + j * 22, x: lane + Math.sin(j * 0.9) * 2, y: low ? DIVE + 2 : 0, big: false });
    starDefs.push({ s: s0 + 5 * 22 + 30, x: -lane * 0.6, y: 0, big: k % 2 === 1 });
  }
  const starGeoItem = new THREE.OctahedronGeometry(1, 0);
  for (const d of starDefs) {
    const g = new THREE.Group(); g.position.copy(at(d.s, d.x, d.y + 1.2));
    const core = new THREE.Mesh(starGeoItem, new THREE.MeshBasicMaterial({ color: d.big ? 0xffffff : 0xffd45a }));
    core.scale.setScalar(d.big ? 1.6 : 0.9);
    const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, color: d.big ? 0xffffff : 0xffc94a, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
    glow.scale.setScalar(d.big ? 9 : 5);
    g.add(core, glow); scene.add(g);
    items.push({ type: 'star', s: d.s, x: d.x, y: d.y, big: d.big, obj: g, core, done: false });
  }
  // 순풍 고리
  for (let k = 0; k < 12; k++) {
    const s = L * (0.09 + k * 0.074), x = (hash(k * 7.7) - 0.5) * 8;
    const g = new THREE.Group(); g.position.copy(at(s, x, 1.5)); g.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), sample(tans, s));
    const ring = new THREE.Mesh(new THREE.TorusGeometry(5.5, 0.38, 8, 40), new THREE.MeshBasicMaterial({ color: 0x9ff0ff }));
    const inner = new THREE.Mesh(new THREE.CircleGeometry(5.3, 32), new THREE.MeshBasicMaterial({ color: 0x9ff0ff, transparent: true, opacity: 0.12, side: THREE.DoubleSide, depthWrite: false }));
    g.add(ring, inner); scene.add(g);
    items.push({ type: 'ring', s, x, y: 0, obj: g, ring, done: false });
  }
  // 먹구름 (길을 막는다. 브레이크로 밑으로 지나간다)
  const stormMat = new THREE.MeshStandardMaterial({ color: 0x3c4250, flatShading: true, roughness: 1, emissive: 0x111318 });
  for (let k = 0; k < 7; k++) {
    const s = L * (0.44 + k * 0.05), x = (hash(k * 5.1) - 0.5) * 6;
    const g = new THREE.Group(); g.position.copy(at(s, x, 2));
    for (let j = 0; j < 9; j++) {
      const r = 2.8 + hash(k * 9 + j) * 2.2;
      const b = new THREE.Mesh(new THREE.IcosahedronGeometry(r, 1), stormMat);
      b.position.set((hash(k + j * 3) - 0.5) * 14, (hash(k + j * 7) - 0.4) * 3.2, (hash(k + j * 11) - 0.5) * 6); b.scale.y = 0.75; g.add(b);
    }
    const bolt = new THREE.Mesh(new THREE.BoxGeometry(0.25, 7, 0.25), new THREE.MeshBasicMaterial({ color: 0xe8f2ff })); bolt.position.y = -4; bolt.rotation.z = 0.3; bolt.visible = false; g.add(bolt);
    scene.add(g);
    items.push({ type: 'storm', s, x, y: 2, obj: g, bolt, done: false });
  }

  // 터지는 빛 입자
  const bursts = [];
  function burst(pos, color, n) {
    const g = new THREE.BufferGeometry(), arr = new Float32Array(n * 3), vel = [];
    for (let i = 0; i < n; i++) { arr.set([pos.x, pos.y, pos.z], i * 3); vel.push(new THREE.Vector3(hash(i + bursts.length) - 0.5, hash(i * 3 + 1) - 0.3, hash(i * 7 + 2) - 0.5).normalize().multiplyScalar(10 + hash(i) * 10)); }
    g.setAttribute('position', new THREE.BufferAttribute(arr, 3));
    const pts2 = new THREE.Points(g, new THREE.PointsMaterial({ color, size: 1.6, map: glowTex, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
    scene.add(pts2); bursts.push({ pts: pts2, vel, age: 0 });
  }

  // ---------- 캐릭터 (제주의 하늘 친구들) ----------
  const flat = (c, extra) => new THREE.MeshStandardMaterial(Object.assign({ color: c, flatShading: true, roughness: 0.7 }, extra || {}));
  function tri(pts) { const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3)); g.computeVertexNormals(); return g; }
  const deltaWing = () => tri([
    0, 0, -2.6, -3.4, 0, 1.2, 0, 0.25, 0.6,   0, 0, -2.6, 0, 0.25, 0.6, 3.4, 0, 1.2,
    0, 0, -2.6, 0, -0.15, 0.7, -3.4, 0, 1.2,  0, 0, -2.6, 3.4, 0, 1.2, 0, -0.15, 0.7,
  ]);
  function makeSeagull() {
    const g = new THREE.Group(), white = flat(0xf7f8fa), grey = flat(0x9aa4ad), dark = flat(0x2c3138), beak = flat(0xffb02e);
    const body = new THREE.Mesh(new THREE.IcosahedronGeometry(1, 1), white); body.scale.set(0.75, 0.7, 2.0); g.add(body);
    const head = new THREE.Mesh(new THREE.IcosahedronGeometry(0.62, 1), white); head.position.set(0, 0.45, -1.75); g.add(head);
    const bk = new THREE.Mesh(new THREE.ConeGeometry(0.2, 0.75, 6), beak); bk.rotation.x = -Math.PI / 2; bk.position.set(0, 0.35, -2.55); g.add(bk);
    for (const sx of [-0.28, 0.28]) { const e = new THREE.Mesh(new THREE.SphereGeometry(0.09, 6, 4), dark); e.position.set(sx, 0.62, -2.1); g.add(e); }
    const tail = new THREE.Mesh(tri([0, 0, 1.6, -0.7, 0, 2.6, 0.7, 0, 2.6]), grey); g.add(tail);
    const wings = [];
    for (const sd of [-1, 1]) {
      const pivot = new THREE.Group(); pivot.position.set(sd * 0.55, 0.15, -0.3);
      const inner = new THREE.Mesh(tri([0, 0, -0.7, sd * 2.0, 0.1, -0.2, 0, 0, 0.8,  0, 0, 0.8, sd * 2.0, 0.1, -0.2, sd * 2.0, 0.05, 0.6]), white);
      const outer = new THREE.Mesh(tri([sd * 2.0, 0.1, -0.2, sd * 3.6, 0.0, 0.6, sd * 2.0, 0.05, 0.6]), grey);
      const tipm = new THREE.Mesh(tri([sd * 3.0, 0.03, 0.3, sd * 3.6, 0.0, 0.6, sd * 3.2, 0.02, 0.62]), dark);
      pivot.add(inner, outer, tipm); g.add(pivot); wings.push({ pivot, sd });
    }
    g.userData.flap = wings; g.userData.tips = [[-3.4, 0, 0.6], [3.4, 0, 0.6]];
    return g;
  }
  function makeTangerine() {
    const g = new THREE.Group(), orange = flat(0xff8a1e, { emissive: 0x3a1600 }), leaf = flat(0x3f8a3a), white = flat(0xf2f4f6), dark = flat(0x2c3138);
    const ball = new THREE.Mesh(new THREE.IcosahedronGeometry(1.6, 1), orange); ball.scale.set(1.05, 0.92, 1.25); g.add(ball);
    const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.1, 0.4, 5), leaf); stem.position.set(0, 1.55, -0.1); g.add(stem);
    const lf = new THREE.Mesh(new THREE.ConeGeometry(0.45, 1.1, 4), leaf); lf.scale.z = 0.25; lf.rotation.z = -1.0; lf.position.set(0.45, 1.65, -0.1); g.add(lf);
    const cab = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.5, 1.2), white); cab.position.set(0, -1.75, 0); g.add(cab);
    for (const sx of [-0.45, 0.45]) { const r = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.03, 0.9, 4), dark); r.position.set(sx, -1.35, 0); g.add(r); }
    for (const sx of [-0.35, 0.35]) { const e = new THREE.Mesh(new THREE.SphereGeometry(0.13, 6, 4), dark); e.position.set(sx, 0.25, -1.88); g.add(e); }
    const fins = [[0, 1, 0], [0, -1, 0], [1, 0, 0], [-1, 0, 0]];
    for (const f of fins) { const m = new THREE.Mesh(tri([0, 0, 1.6, f[0] * 0.9 + f[1] * 0, f[1] * 0.9, 2.3, 0, 0, 2.4]), orange); g.add(m); }
    const prop = new THREE.Group(); prop.position.set(0, 0, 2.45); g.add(prop);
    for (let k = 0; k < 3; k++) { const b = new THREE.Mesh(new THREE.BoxGeometry(0.16, 1.1, 0.05), white); b.position.y = 0.55; const a = new THREE.Group(); a.rotation.z = k * 2.094; a.add(b); prop.add(a); }
    g.userData.prop = prop; g.userData.tips = [[-1.7, 0, 0.5], [1.7, 0, 0.5]];
    return g;
  }
  function makeHareubang() {
    const g = new THREE.Group(), basalt = flat(0x55545c, { roughness: 1 }), sail = flat(0x2fb7c9, { side: THREE.DoubleSide }), sail2 = flat(0xff9a3c, { side: THREE.DoubleSide }), bar = flat(0x222428);
    const w = new THREE.Mesh(deltaWing(), sail); w.position.y = 2.0; w.scale.set(1.05, 1, 1); g.add(w);
    const stripe = new THREE.Mesh(tri([0, 0.27, -2.4, -1.2, 0.08, 0.5, 1.2, 0.08, 0.5]), sail2); stripe.position.y = 2.01; g.add(stripe);
    for (const sx of [-0.5, 0.5]) { const r = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.04, 1.7, 4), bar); r.position.set(sx, 1.1, -0.2); r.rotation.z = sx * 0.25; g.add(r); }
    const bt = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.05, 1.6, 4), bar); bt.rotation.z = Math.PI / 2; bt.position.set(0, 0.3, -0.4); g.add(bt);
    const man = new THREE.Group(); man.position.set(0, -0.6, 0.2); man.rotation.x = -1.2;
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.55, 0.65, 1.8, 7), basalt); man.add(body);
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.6, 7, 5), basalt); head.position.y = 1.3; head.scale.y = 1.15; man.add(head);
    const hat = new THREE.Mesh(new THREE.CylinderGeometry(0.5, 0.62, 0.5, 7), basalt); hat.position.y = 1.95; man.add(hat);
    const nose = new THREE.Mesh(new THREE.SphereGeometry(0.2, 5, 4), basalt); nose.position.set(0, 1.25, 0.58); man.add(nose);
    for (const sx of [-0.22, 0.22]) { const e = new THREE.Mesh(new THREE.SphereGeometry(0.11, 5, 4), flat(0x2a2a30)); e.position.set(sx, 1.45, 0.52); man.add(e); }
    g.add(man);
    g.userData.tips = [[-3.5, 2.0, 1.2], [3.5, 2.0, 1.2]];
    return g;
  }
  function makePaperPlane() {
    const g = new THREE.Group(), paper = flat(0xfbfbf8, { side: THREE.DoubleSide, roughness: 0.9 }), crease = flat(0xd9dde3, { side: THREE.DoubleSide });
    g.add(new THREE.Mesh(tri([0, 0, -3.2, -2.8, 0.15, 1.6, 0, -0.05, 1.6]), paper));
    g.add(new THREE.Mesh(tri([0, 0, -3.2, 0, -0.05, 1.6, 2.8, 0.15, 1.6]), paper));
    g.add(new THREE.Mesh(tri([0, 0, -3.2, 0, -0.05, 1.6, 0, -0.9, 1.4]), crease));
    const line = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 4.6), flat(0x7fe8ff)); line.position.set(-1.0, 0.07, 0.0); line.rotation.y = 0.36; g.add(line);
    const line2 = line.clone(); line2.position.x = 1.0; line2.rotation.y = -0.36; g.add(line2);
    g.userData.tips = [[-2.8, 0.15, 1.6], [2.8, 0.15, 1.6]];
    return g;
  }
  const CHARS = [
    { id: 'gull',  name: '바당이',   who: '제주 갈매기',          desc: '핸들이 민첩해요',             color: '#7fd0ff', ambient: 0x6EC8FF, light: '하늘색', stats: [3, 5, 2], make: makeSeagull,    vmax: 1.0,  steer: 1.35, magnet: 1.0, guard: 1.0 },
    { id: 'tang',  name: '귤돌이',   who: '감귤 비행선',          desc: '별빛을 끌어당겨요',           color: '#ff9a3c', ambient: 0xFF8A1E, light: '주황',   stats: [3, 3, 3], make: makeTangerine,  vmax: 0.97, steer: 1.0,  magnet: 2.0, guard: 1.0 },
    { id: 'harb',  name: '하르방',   who: '돌하르방 행글라이더',  desc: '묵직해서 먹구름에 덜 느려져요', color: '#a89ad8', ambient: 0x7A6FB5, light: '현무암 보라', stats: [3, 2, 5], make: makeHareubang, vmax: 0.98, steer: 0.85, magnet: 1.0, guard: 2.0 },
    { id: 'paper', name: '종이비행기', who: '접은 종이비행기',      desc: '최고 속도가 빨라요',           color: '#ffffff', ambient: 0xFFFFFF, light: '흰색',   stats: [5, 3, 1], make: makePaperPlane, vmax: 1.12, steer: 1.0,  magnet: 1.0, guard: 0.7 },
  ];
  let char = CHARS[0];
  const glider = new THREE.Group();
  let model = null;
  function setCharacter(c) {
    char = c;
    if (model) glider.remove(model);
    model = c.make(); glider.add(model);
  }
  setCharacter(CHARS[0]);
  glider.scale.setScalar(0.72);
  scene.add(glider);
  // 날개 끝 바람 자국
  const TRAIL = 40;
  function makeTrail() {
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(TRAIL * 3), 3));
    const l = new THREE.Line(g, new THREE.LineBasicMaterial({ color: 0xdffaff, transparent: true, opacity: 0.5 })); l.frustumCulled = false; scene.add(l); return { line: l, pts: [] };
  }
  const trails = [makeTrail(), makeTrail()];

  // ---------- 소리 (파일 없이 직접 만든다) ----------
  const audio = (() => {
    let ctx = null, windGain, windFilter, master;
    function init() {
      if (ctx) return;
      try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return; }
      master = ctx.createGain(); master.gain.value = 0.8; master.connect(ctx.destination);
      const len = ctx.sampleRate * 2, buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
      let last = 0; for (let i = 0; i < len; i++) { const w = Math.random() * 2 - 1; last = (last + 0.02 * w) / 1.02; d[i] = last * 3.5; }   // 부드러운 바람 소리
      const src = ctx.createBufferSource(); src.buffer = buf; src.loop = true;
      windFilter = ctx.createBiquadFilter(); windFilter.type = 'lowpass'; windFilter.frequency.value = 400; windFilter.Q.value = 0.7;
      windGain = ctx.createGain(); windGain.gain.value = 0;
      src.connect(windFilter); windFilter.connect(windGain); windGain.connect(master); src.start();
    }
    function tone(freq, t0, dur, type, vol, to) {
      if (!ctx) return; const o = ctx.createOscillator(), g = ctx.createGain(); o.type = type || 'sine';
      o.frequency.setValueAtTime(freq, ctx.currentTime + t0); if (to) o.frequency.exponentialRampToValueAtTime(to, ctx.currentTime + t0 + dur);
      g.gain.setValueAtTime(0.0001, ctx.currentTime + t0); g.gain.exponentialRampToValueAtTime(vol || 0.2, ctx.currentTime + t0 + 0.015);
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + t0 + dur); o.connect(g); g.connect(master); o.start(ctx.currentTime + t0); o.stop(ctx.currentTime + t0 + dur + 0.05);
    }
    function noise(dur, freq, vol, attack) {
      if (!ctx) return; const len = Math.floor(ctx.sampleRate * dur), buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1);
      const s = ctx.createBufferSource(); s.buffer = buf; const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = freq;
      const g = ctx.createGain(); const t = ctx.currentTime; g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(vol, t + (attack || 0.02)); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      s.connect(f); f.connect(g); g.connect(master); s.start();
    }
    return {
      init, resume() { init(); if (ctx && ctx.state !== 'running') ctx.resume(); },
      wind(speed01, boost) {
        if (!ctx) return; const t = ctx.currentTime;
        windGain.gain.setTargetAtTime(0.06 + speed01 * 0.28 + (boost ? 0.18 : 0), t, 0.25);
        windFilter.frequency.setTargetAtTime(350 + speed01 * 1500 + (boost ? 900 : 0), t, 0.25);
      },
      star(big) { if (big) { [1047, 1319, 1568, 2093].forEach((f, i) => tone(f, i * 0.07, 0.6, 'sine', 0.16)); } else { tone(1568, 0, 0.35, 'sine', 0.13); tone(2349, 0.05, 0.4, 'sine', 0.08); } },
      boost() { tone(220, 0, 0.6, 'sawtooth', 0.06, 880); noise(0.7, 2500, 0.25, 0.08); },
      thunder(near) { noise(2.2, near ? 260 : 160, near ? 0.7 : 0.35, 0.03); },
      hit() { tone(90, 0, 0.5, 'sine', 0.4, 45); noise(0.6, 400, 0.4); },
      beep(hi) { tone(hi ? 1320 : 880, 0, hi ? 0.5 : 0.18, 'sine', 0.18); },
      dash() { noise(0.35, 3500, 0.3, 0.02); },
      finish() { [523, 659, 784, 1047, 1319].forEach((f, i) => tone(f, i * 0.12, 0.9, 'triangle', 0.14)); },
    };
  })();

  // ---------- 상태 ----------
  const st = {
    phase: 'wait', t: 0, phaseT: 0, s: 0, x: 0, y: 0, v: 0, boost: 0,
    steer: 0, brake: false, score: 0, stars: 0, onTrack: 0, flyTime: 0, streak: 0, mult: 1,
    zone: -1, windLevel: -1, shake: 0, finished: false,
    turn: 0, lastTurn: 0, dash: 0, belt: -1, passenger: false, beepN: -1, lightningT: 0,
  };
  const $ = (id) => document.getElementById(id);
  let toastTimer = 0;
  function toast(text, color) { const el = $('toast'); el.textContent = text; el.style.color = color || '#fff'; el.style.opacity = 1; toastTimer = 1.3; }
  function showZone(z) {
    const el = $('zone'); el.querySelector('.t').textContent = z.title; el.querySelector('.c').textContent = z.caption; el.style.opacity = 1;
    setTimeout(() => { el.style.opacity = 0; }, 4200);
  }
  function center(big, msg, msg2) { $('big').textContent = big || ''; $('msg').textContent = msg || ''; $('msg2').textContent = msg2 || ''; }
  function flash(a) { const f = $('flash'); f.style.transition = 'none'; f.style.opacity = a; requestAnimationFrame(() => { f.style.transition = 'opacity .45s'; f.style.opacity = 0; }); }

  // ---------- 앱과 주고받기 ----------
  window.race = {
    input(steer, brake, turn, belt, passenger) {
      st.turn = turn | 0; st.belt = belt === undefined ? -1 : belt; st.passenger = !!passenger;
      if (AUTO) return; st.steer = clamp(steer, -1, 1); st.brake = !!brake;
    },
    start() { audio.resume(); if (st.phase === 'wait') { st.phase = 'select'; st.phaseT = 0; showSelect(true); } },
    pick(i) { if (st.phase === 'select') { selIndex = i; renderCards(); confirmPick(); } },
    stop() { st.phase = 'done'; },
  };

  // ---------- 캐릭터 고르기 ----------
  let selIndex = 0, lastSteerDir = 0, lastBrake = false;
  function best(id) { try { return +(localStorage.getItem('best_' + id) || 0); } catch (e) { return 0; } }
  function renderCards() {
    const el = $('cards'); el.innerHTML = '';
    CHARS.forEach((c, i) => {
      const d = document.createElement('div'); d.className = 'card' + (i === selIndex ? ' on' : ''); d.style.setProperty('--c', c.color);
      const bar = (label, v) => `<div class="r"><span>${label}</span><b><i style="width:${v * 20}%"></i></b></div>`;
      d.innerHTML = `<div class="n">${c.name}</div><div class="d">${c.who} · ${c.desc}</div>` + bar('속도', c.stats[0]) + bar('핸들링', c.stats[1]) + bar('버티기', c.stats[2]) +
        `<div class="best">무드등 ${c.light}${best(c.id) ? ' · 최고 ' + best(c.id) : ''}</div>`;
      d.onclick = () => { if (selIndex === i) confirmPick(); else { selIndex = i; renderCards(); previewPick(); } };
      el.appendChild(d);
    });
  }
  function previewPick() { setCharacter(CHARS[selIndex]); audio.beep(false); send({ type: 'character', id: char.id, name: char.name, ambient: char.ambient, light: char.light, confirm: false }); }
  function confirmPick() {
    setCharacter(CHARS[selIndex]); showSelect(false); audio.star(true);
    send({ type: 'character', id: char.id, name: char.name, ambient: char.ambient, light: char.light, confirm: true });
    toast(char.name + '와 함께 출발 준비!', char.color); st.phase = 'calib'; st.phaseT = 0;
    if (AUTO) { st.steer = 0; st.brake = false; mirrorT = -1; mirrorAuto(); }
  }
  function showSelect(on) { $('select').style.display = on ? 'flex' : 'none'; if (on) { renderCards(); previewPick(); center('', '', ''); } }
  function selectStep() {
    // 핸들을 크게 돌리면 옆 캐릭터로, 브레이크를 밟으면 결정
    const dir = st.steer > 0.5 ? 1 : st.steer < -0.5 ? -1 : 0;
    if (dir !== 0 && dir !== lastSteerDir) { selIndex = (selIndex + dir + CHARS.length) % CHARS.length; renderCards(); previewPick(); }
    lastSteerDir = dir;
    if (st.brake && !lastBrake && st.phaseT > 0.6) confirmPick();
    lastBrake = st.brake;
    if (AUTO) {   // 시연: 핸들을 한 번 돌려 넘기고 브레이크로 결정
      st.steer = st.phaseT > 1.0 && st.phaseT < 1.5 ? 0.9 : 0; st.brake = st.phaseT > 2.6; mirrorAuto();
    }
  }

  function placeGlider(dt) {
    const c = sample(pts, st.s), t = sample(tans, st.s), sd = sample(sides, st.s);
    const p = c.clone().addScaledVector(sd, st.x).addScaledVector(UP, st.y + 1.2);
    glider.position.copy(p);
    const look = p.clone().add(t);
    glider.lookAt(look);
    const bank = -st.steer * 0.6 + curvature(st.s) * 60;
    glider.rotateZ(clamp(bank, -0.8, 0.8));
    glider.rotateX(st.brake ? 0.18 : -0.04);
    // 카메라: 뒤에서 따라간다. 빠를수록 시야가 넓어진다
    let camPos = p.clone().addScaledVector(t, -14).addScaledVector(UP, 4.6);
    if (st.phase === 'select') { const a = st.t * 0.5; camPos = p.clone().add(new THREE.Vector3(Math.sin(a) * 9, 2.2, Math.cos(a) * 9 - 2)); }
    if (st.shake > 0) camPos.add(new THREE.Vector3((Math.random() - 0.5) * st.shake, (Math.random() - 0.5) * st.shake, 0));
    camera.position.lerp(camPos, st.phase === 'select' ? 1 : 1 - Math.pow(0.0005, dt));
    const target = st.phase === 'select' ? p.clone().addScaledVector(UP, -0.6) : p.clone().addScaledVector(t, 22).addScaledVector(UP, 1.2);
    camera.lookAt(target);
    camera.fov = lerp(camera.fov, 62 + clamp((st.v - V_MIN) / (V_MAX + BOOST - V_MIN), 0, 1) * 16, 0.08); camera.updateProjectionMatrix();
    // 날개 끝 자국
    (model.userData.tips || [[-3.4, 0, 1.2], [3.4, 0, 1.2]]).forEach((tp, k) => {
      const tip = new THREE.Vector3(tp[0], tp[1], tp[2]).applyMatrix4(glider.matrixWorld);
      const tr = trails[k]; tr.pts.unshift(tip); if (tr.pts.length > TRAIL) tr.pts.pop();
      const a = tr.line.geometry.attributes.position; for (let i = 0; i < TRAIL; i++) { const q = tr.pts[Math.min(i, tr.pts.length - 1)]; a.setXYZ(i, q.x, q.y, q.z); }
      a.needsUpdate = true;
    });
  }

  function updateSky(p) {
    const k = skyAt(p);
    skyMat.uniforms.top.value.copy(k.top); skyMat.uniforms.bottom.value.copy(k.bottom);
    const dir = new THREE.Vector3(0.35, k.el, -1).normalize();
    skyMat.uniforms.sunDir.value.copy(dir); skyMat.uniforms.sunCol.value.copy(k.sun);
    sun.color.copy(k.sun); sun.intensity = k.sunI; sun.position.copy(glider.position).addScaledVector(dir, 400); sun.target.position.copy(glider.position);
    hemi.intensity = k.hemi;
    scene.fog.color.copy(k.fog);
    // 한라산 구름 속: 안개가 짙어진다
    const inCloud = smooth(0.655, 0.675, p) * (1 - smooth(0.715, 0.735, p));
    scene.fog.near = lerp(140, 8, inCloud); scene.fog.far = lerp(1300, 170, inCloud);
    if (inCloud > 0) scene.fog.color.lerp(new THREE.Color(0xf4f7fb), inCloud);
    nightStars.material.opacity = 1 - smooth(0.04, 0.2, p);
    sky.position.copy(camera.position); nightStars.position.copy(camera.position);
    sea.position.set(Math.round(camera.position.x / SEA_CELL) * SEA_CELL, 0, Math.round(camera.position.z / SEA_CELL) * SEA_CELL);
    renderer.setClearColor(k.fog);
  }

  // ---------- 매 프레임 ----------
  let last = performance.now(), fpsN = 0, fpsT = 0;
  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now;
    // 화면 크기가 늦게 정해지는 경우가 있어 매 프레임 맞춘다 (WebView는 처음에 0×0일 수 있다)
    const cw = window.innerWidth, ch = window.innerHeight, sz = renderer.getSize(new THREE.Vector2());
    if (cw > 0 && ch > 0 && (sz.x !== cw || sz.y !== ch)) { renderer.setSize(cw, ch); camera.aspect = cw / ch; camera.updateProjectionMatrix(); }
    st.t += dt; st.phaseT += dt;
    seaUniforms.uTime.value = st.t; ribbonMat.uniforms.uTime.value = st.t;
    blades.forEach((b, i) => { b.rotation.z += dt * (1.2 + i * 0.07); });
    if (model.userData.flap) model.userData.flap.forEach((w) => { w.pivot.rotation.z = w.sd * (Math.sin(st.t * (st.phase === 'race' ? 7 : 4)) * 0.45); });
    if (model.userData.prop) model.userData.prop.rotation.z += dt * 18;

    // 높이: 브레이크를 밟으면 내려가고, 떼면 올라온다
    st.y = st.brake ? Math.max(DIVE, st.y - 20 * dt) : Math.min(0, st.y + 14 * dt);

    if (st.phase === 'select') {
      selectStep();
    } else if (st.phase === 'calib') {
      st.x = clamp(st.x + st.steer * STEER_RATE * dt, -HALF_W, HALF_W);
      if (st.belt === 0) {
        // 안전벨트를 매야 출발한다
        center('🔒', '안전벨트를 매 주세요', '벨트를 매면 출발 준비를 시작해요'); st.phaseT = 0;
      } else {
        center('', '핸들을 돌리고, 브레이크를 밟아 보세요', (st.belt === 1 ? '✓ 안전벨트 · ' : '') + '핸들은 좌우 · 브레이크는 아래로 · 깜빡이는 대시 · ' + Math.ceil(3 - st.phaseT));
        if (st.phaseT >= 3) { st.phase = 'count'; st.phaseT = 0; }
      }
    } else if (st.phase === 'count') {
      st.x = clamp(st.x + st.steer * STEER_RATE * dt, -HALF_W, HALF_W);
      const n = Math.ceil(3 - st.phaseT);
      center(String(n), '', '');
      if (n !== st.beepN) { st.beepN = n; if (n > 0) audio.beep(false); }
      if (st.phaseT >= 3) { st.phase = 'race'; st.phaseT = 0; center('', '', ''); st.v = V_MIN; toast('출발!', '#9ff0ff'); audio.beep(true); }
    } else if (st.phase === 'race') {
      race(dt);
    } else if (st.phase === 'finish') {
      st.v = lerp(st.v, 12, 0.03); st.s = Math.min(L, st.s + st.v * dt);
      if (st.phaseT > 2.2 && !st.finished) {
        st.finished = true;
        try { const sc = Math.round(st.score); if (sc > best(char.id)) localStorage.setItem('best_' + char.id, sc); } catch (e) {}
        send({ type: 'finish', character: char.name, score: Math.round(st.score), stars: st.stars, starsTotal: starDefs.length, onTrackPct: Math.round(st.onTrack / Math.max(1, st.flyTime) * 100), timeSec: Math.round(st.flyTime) });
      }
    }

    // 아이템 움직임
    for (const it of items) {
      if (it.done) continue;
      const d = Math.abs(it.s - st.s); if (d > 700) { it.obj.visible = false; continue; } it.obj.visible = true;
      if (it.type === 'star') {
        it.core.rotation.y += dt * 2.2; it.obj.position.y += Math.sin(st.t * 3 + it.s) * 0.004;
        if (char.magnet > 1 && st.phase === 'race' && it.s - st.s < 60 && it.s - st.s > -3) {   // 자석: 별이 귤돌이 쪽으로 끌려온다
          it.obj.position.lerp(glider.position, 0.035); it.x = lerp(it.x, st.x, 0.035); it.y = lerp(it.y, st.y, 0.035);
        }
      }
      if (it.type === 'ring') it.ring.rotation.z += dt * 0.8;
      if (it.type === 'storm') it.bolt.visible = Math.sin(st.t * 7.3 + it.s) > 0.93;
    }
    for (let i = bursts.length - 1; i >= 0; i--) {
      const b = bursts[i]; b.age += dt; const a = b.pts.geometry.attributes.position;
      for (let k = 0; k < b.vel.length; k++) { a.setXYZ(k, a.getX(k) + b.vel[k].x * dt, a.getY(k) + b.vel[k].y * dt, a.getZ(k) + b.vel[k].z * dt); }
      a.needsUpdate = true; b.pts.material.opacity = Math.max(0, 1 - b.age / 0.8);
      if (b.age > 0.8) { scene.remove(b.pts); b.pts.geometry.dispose(); bursts.splice(i, 1); }
    }

    st.shake = Math.max(0, st.shake - dt * 3);
    if (toastTimer > 0) { toastTimer -= dt; if (toastTimer <= 0) $('toast').style.opacity = 0; }
    placeGlider(dt);
    updateSky(st.s / L);
    $('score').textContent = Math.round(st.score);
    $('sub').textContent = '★ ' + st.stars + (st.mult > 1 ? '   순풍 ×' + st.mult : '') + (st.passenger ? '   👥 2인 비행' : '');
    $('speed').querySelector('.v').textContent = Math.round(st.v * 2.4);
    $('bar').firstChild.style.width = clamp((st.v - V_MIN) / (V_MAX + BOOST - V_MIN), 0, 1) * 100 + '%';
    renderer.render(scene, camera);
    if (DEBUG && fpsT + dt > 2) {
      const gl = renderer.getContext(), px = new Uint8Array(4);
      gl.readPixels(gl.drawingBufferWidth >> 1, gl.drawingBufferHeight >> 2, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      console.log('pixel ' + Array.from(px).join(',') + ' err ' + gl.getError() + ' lost ' + gl.isContextLost() + ' buf ' + gl.drawingBufferWidth + 'x' + gl.drawingBufferHeight + ' canvas ' + renderer.domElement.clientWidth + 'x' + renderer.domElement.clientHeight);
    }

    if (DEBUG) { fpsN++; fpsT += dt; if (fpsT > 2) { const c = camera.position, g = glider.position; console.log('fps ' + (fpsN / fpsT).toFixed(1) + ' calls ' + renderer.info.render.calls + ' tris ' + renderer.info.render.triangles + ' cam ' + c.x.toFixed(1) + ',' + c.y.toFixed(1) + ',' + c.z.toFixed(1) + ' gl ' + g.x.toFixed(1) + ',' + g.y.toFixed(1) + ',' + g.z.toFixed(1) + ' fog ' + scene.fog.near.toFixed(0) + '-' + scene.fog.far.toFixed(0)); fpsN = 0; fpsT = 0; } }
    requestAnimationFrame(frame);
  }

  // 자동 조종 (테스트용 ?autopilot): 앞의 아이템을 향해 핸들을 꺾고, 낮은 별·먹구름 앞에서는 브레이크
  function autopilot() {
    let tx = 0, dive = false;
    for (const it of items) {
      if (it.done || it.s < st.s - 2 || it.s > st.s + 70) continue;
      if (it.type === 'storm') { dive = true; continue; }
      tx = it.x; if (it.type === 'star' && it.y < -3) dive = true; break;
    }
    const drift = curvature(st.s + 10) * st.v * st.v;
    st.steer = clamp(((tx - st.x) * 0.9 + drift) / STEER_RATE, -1, 1);
    st.brake = dive;
    mirrorAuto();
  }
  // 자동 조종 값을 앱에 알린다 (시연 영상에서 콘솔 핸들·브레이크가 같이 움직이도록)
  let mirrorT = 0, mirrorDeg = null, mirrorBrake = null;
  function mirrorAuto() {
    if (st.t - mirrorT < 0.12) return;
    const deg = Math.round(st.steer * 60 / 3) * 3;
    if (deg === mirrorDeg && st.brake === mirrorBrake) return;
    mirrorT = st.t; mirrorDeg = deg; mirrorBrake = st.brake;
    send({ type: 'autosteer', deg, brake: st.brake });
  }
  const AUTO = Q.has('autopilot');

  function race(dt) {
    if (AUTO) autopilot();
    // 방향지시등: 켜는 순간 그쪽으로 순간 대시
    if (st.turn !== 0 && st.lastTurn === 0) {
      st.dash = st.turn * 1; st.boost = Math.max(st.boost, 14); audio.dash();
      toast(st.turn < 0 ? '◀ 왼쪽 대시' : '오른쪽 대시 ▶', '#9ff0ff'); send({ type: 'dash', dir: st.turn });
    }
    st.lastTurn = st.turn;
    const dashV = st.dash * 26; st.dash *= Math.pow(0.02, dt);
    // 옆으로: 핸들 + 굽은 길에서 바깥으로 밀리는 힘 + 대시
    const drift = curvature(st.s) * st.v * st.v * 1.0;
    st.x = clamp(st.x + (st.steer * STEER_RATE * char.steer - drift + dashV) * dt, -38, 38);
    const onTrack = Math.abs(st.x) <= HALF_W && st.y > -7;
    // 속도: 바람길 안이면 빨라지고, 밖이면 느려진다. 순풍 고리는 잠깐 가속
    const target = (onTrack ? V_MAX * char.vmax : V_MIN) + st.boost;
    st.v = lerp(st.v, target, 1 - Math.pow(onTrack ? 0.55 : 0.25, dt));
    st.boost = Math.max(0, st.boost - dt * 22);
    st.s += st.v * dt;
    st.flyTime += dt;
    ribbonMat.uniforms.uGlow.value = onTrack ? 1 : 0;
    if (onTrack) {
      st.onTrack += dt; st.streak += dt;
      const m = 1 + Math.min(2, Math.floor(st.streak / 4));
      if (m > st.mult) toast('순풍 ×' + m, '#9ff0ff');
      st.mult = m; st.score += 30 * st.mult * dt;
    } else { st.streak = 0; st.mult = 1; }

    audio.wind(clamp((st.v - V_MIN) / (V_MAX + BOOST - V_MIN), 0, 1), st.boost > 5);
    // 먹구름 근처의 번개: 천둥 소리 + 실내등 번쩍
    for (const it of items) {
      if (it.type !== 'storm' || it.done) continue;
      const d = it.s - st.s;
      if (d > 0 && d < 260 && it.bolt.visible && st.t - st.lightningT > 1.6) {
        st.lightningT = st.t; audio.thunder(d < 120); flash(d < 120 ? 0.35 : 0.18); send({ type: 'lightning', near: d < 120 });
      }
    }
    // 차 바람 세기 = 속도 (0~3단)
    const level = clamp(Math.round((st.v - V_MIN) / (V_MAX - V_MIN) * 3), 0, 3);
    if (level !== st.windLevel) { st.windLevel = level; send({ type: 'wind', level }); }

    // 구간
    const p = st.s / L;
    let z = 0; while (z < ZONES.length - 1 && p >= ZONES[z + 1].p) z++;
    if (z !== st.zone) { st.zone = z; const Z = ZONES[z]; showZone(Z); send(Object.assign({ type: 'zone' }, Z)); }

    // 아이템 판정
    const me = glider.position;
    for (const it of items) {
      if (it.done || st.s < it.s - 3) continue;
      if (st.s > it.s + 4) { if (it.type !== 'star') it.done = true; else if (st.s > it.s + 40) it.done = true; continue; }
      const dx = st.x - it.x, dy = st.y - it.y;
      if (it.type === 'star' && Math.hypot(dx, dy) < (it.big ? 4.2 : 3.4) * char.magnet) {
        it.done = true; it.obj.visible = false; st.stars++; st.score += it.big ? 150 : 50;
        burst(it.obj.position, it.big ? 0xffffff : 0xffd45a, it.big ? 40 : 18);
        if (it.big) { toast('황금 별 +150', '#ffe6a0'); flash(0.35); }
        audio.star(it.big); send({ type: 'star', big: it.big });
      } else if (it.type === 'ring' && st.s >= it.s) {
        it.done = true;
        if (Math.hypot(dx, dy) < 5.6) { st.boost = BOOST; st.score += 100; toast('순풍 가속!', '#9ff0ff'); burst(it.obj.position, 0x9ff0ff, 26); audio.boost(); send({ type: 'boost' }); }
      } else if (it.type === 'storm' && st.s >= it.s) {
        it.done = true;
        if (Math.abs(dx) < 8 && st.y > -6) {
          const pen = Math.round(100 / char.guard); st.score = Math.max(0, st.score - pen); st.v *= char.guard > 1 ? 0.82 : 0.55; st.boost = 0; st.streak = 0; st.mult = 1; st.shake = 2.2 / char.guard;
          toast((char.guard > 1 ? '하르방은 끄떡없어요 -' : '먹구름에 부딪혔어요 -') + pen, '#ff8a80'); flash(0.25); audio.hit(); send({ type: 'storm', hit: true });
        } else { toast('먹구름을 피했어요', '#ffffff'); send({ type: 'storm', hit: false }); }
      }
    }

    if (st.s >= L * 0.985) { st.phase = 'finish'; st.phaseT = 0; toast('도착!', '#ffe6a0'); audio.finish(); center('', '성산일출봉 도착', '점수 ' + Math.round(st.score)); }
  }

  // 시작 화면: 첫 장면을 미리 그려 둔다
  st.s = 0; placeGlider(1); camera.position.copy(glider.position).add(new THREE.Vector3(0, 5, 15)); updateSky(0);
  center('', '제주 하늘 레이스', '준비 중…');
  requestAnimationFrame((n) => { last = n; requestAnimationFrame(frame); });
  send({ type: 'ready' });
  if (Q.has('auto')) setTimeout(() => window.race.start(), 500);
})();
