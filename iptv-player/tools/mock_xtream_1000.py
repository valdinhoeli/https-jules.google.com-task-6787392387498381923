"""Painel Xtream simulado: 1000 canais, 1000 filmes e 1000 séries (teste de funcionamento).

Uso: python3 mock_xtream_1000.py <pasta>  — a pasta precisa de img/ (pN.jpg, bN.jpg, lN.png) e
media/ (live.ts, vod.mp4). No emulador Android o servidor fica em http://10.0.2.2:8001
(lista Xtream com qualquer usuário/senha)."""
import json, os, re, sys, time, random
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

ROOT = sys.argv[1]
HOST = "http://10.0.2.2:8001"
R = random.Random(7)
T0 = int(time.time())

# ---------------- canais (1000) ----------------
CH_GROUPS = [("Abertos", 100, "Rede Aberta"), ("Notícias", 100, "Notícias 24"), ("Esportes", 150, "Esporte Total"),
             ("Filmes", 150, "Cine Ação"), ("Séries", 100, "Séries Max"), ("Infantil", 120, "Kids Fun"),
             ("Novelas", 80, "Viva Novelas"), ("Documentários", 100, "Docs Mundo"), ("Música", 80, "Música Hits"),
             ("Adultos", 20, "Privê")]
CHANNELS = []  # (id, nome, grupo, tvg-id)
n = 0
for g, cnt, base in CH_GROUPS:
    for k in range(1, cnt + 1):
        n += 1
        q = ["HD", "FHD", "4K", "SD"][k % 4]
        CHANNELS.append((n, f"{base} {k} {q}", g, f"c{n}.br"))
assert len(CHANNELS) == 1000

TEAMS = ["Flamengo", "Palmeiras", "Corinthians", "São Paulo", "Santos", "Grêmio", "Internacional", "Cruzeiro", "Atlético",
         "Vasco", "Botafogo", "Fluminense", "Bahia", "Fortaleza", "Athletico", "Coritiba", "Sport", "Ceará", "Goiás", "Vitória"]
COMPS = ["Brasileirão", "Libertadores", "Copa do Brasil", "Sul-Americana", "Paulistão", "Carioca", "Champions League", "NBA", "Superliga de Vôlei"]
FILMS = ["Rocky x Drago: A Luta Final", "Creed: Boxe x Campeão", "Velocidade Máxima", "O Grande Golpe", "Missão Atlântida",
         "Código Vermelho", "Duro de Matar", "A Fuga", "Tempestade", "O Guardião"]
NOVELAS = ["Terra Nostra", "O Clone", "Mulheres Apaixonadas", "Avenida Brasil", "Laços de Família", "Senhora do Destino"]

def schedule(cid, group, idx):
    """Programas de 3 h antes até 21 h depois (blocos de 60/90/120 min)."""
    rnd = random.Random(cid)
    t = T0 - T0 % 1800 - 3 * 3600 - rnd.choice([0, 900, 1800])
    out, k = [], 0
    while t < T0 + 21 * 3600:
        dur = rnd.choice([60, 90, 120]) * 60
        live_now = t <= T0 < t + dur
        cat, extra = "", ""
        if group == "Esportes":
            a, b = TEAMS[(idx * 3 + k) % 20], TEAMS[(idx * 7 + k * 5 + 1) % 20]
            if a == b: b = TEAMS[(TEAMS.index(a) + 1) % 20]
            comp = COMPS[(idx + k) % len(COMPS)]
            kind = (idx + k) % 5
            if live_now and idx % 3 == 0: title, extra = f"{comp}: {a} x {b}", "<live/>"
            elif kind == 1: title = "Resenha Esportiva"
            elif kind == 2: title = f"{comp}: {a} x {b} (VT)"
            elif kind == 3: title = "Gols da Rodada"
            else: title = f"{comp}: {a} x {b}"
            cat = "Esportes"
        elif group == "Filmes":
            title, cat = FILMS[(idx + k) % len(FILMS)], "Filme"
        elif group == "Séries":
            title, cat = f"Série: Temporada {k % 5 + 1}", "Série"
        elif group == "Novelas":
            title, cat = f"Novela: {NOVELAS[(idx + k) % len(NOVELAS)]}", "Novela"
        elif group == "Abertos":
            if (k + idx) % 4 == 0: title, cat = "Novela das Nove: Amor Eterno", "Novela"
            else: title = ["Jornal da Manhã", "Programa da Tarde", "Jornal Nacional", "Futebol: Resenha"][(k + idx) % 4]
        elif group == "Infantil":
            title = ["Desenhos da Manhã", "Aventuras do Robô", "Turma da Floresta", "Ciência Divertida"][(k + idx) % 4]
        elif group == "Notícias":
            title = ["Jornal 24h", "Boletim Econômico", "Mundo Hoje", "Debate"][(k + idx) % 4]
        else:
            title = f"{group}: programa {k + 1}"
        out.append((t, t + dur, title, cat, extra))
        t += dur; k += 1
    return out

def xmltv():
    fmt = lambda x: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(x))
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="mock1000">']
    for cid, name, g, tvg in CHANNELS:
        out.append(f'<channel id="{tvg}"><display-name>{name}</display-name></channel>')
    idx_in_group = {}
    for cid, name, g, tvg in CHANNELS:
        idx = idx_in_group.get(g, 0); idx_in_group[g] = idx + 1
        for s, e, title, cat, extra in schedule(cid, g, idx):
            c = f'<category lang="pt">{cat}</category>' if cat else ""
            out.append(f'<programme start="{fmt(s)}" stop="{fmt(e)}" channel="{tvg}"><title lang="pt">{title}</title>'
                       f'<desc lang="pt">{title} — programa do guia de teste.</desc>{c}{extra}</programme>')
    out.append("</tv>")
    return "\n".join(out).encode()

# ---------------- filmes (1000) e séries (1000) ----------------
VOD_CATS = [("Ação", 200), ("Comédia", 200), ("Drama", 150), ("Terror", 100), ("Infantil", 100), ("Animação", 80),
            ("Esportes", 70), ("Documentário", 70), ("Adultos +18", 30)]
SER_CATS = [("Drama", 250), ("Comédia", 150), ("Suspense", 150), ("Novelas", 150), ("Desenhos", 120), ("Reality", 80),
            ("Documentários", 50), ("Esportes", 50)]
W1 = ["Noite", "Caminho", "Segredo", "Cidade", "Mar", "Sombra", "Luz", "Fogo", "Vento", "Terra", "Coração", "Destino"]
W2 = ["Perdido", "Dourado", "Proibido", "Eterno", "Selvagem", "Secreto", "Distante", "Final", "Vermelho", "Azul"]
MOVIES, SERIES = [], []
i = 0
for ci, (c, cnt) in enumerate(VOD_CATS):
    for k in range(cnt):
        age = "L" if c in ("Infantil", "Animação") else ("18" if c.startswith("Adultos") else R.choice(["L", "10", "12", "14", "16"]))
        MOVIES.append({"num": i + 1, "name": f"{R.choice(W1)} {R.choice(W2)} {i + 1:04d}", "stream_type": "movie",
                       "stream_id": 10001 + i, "stream_icon": f"{HOST}/img/p{i % 26}.jpg" if i % 9 else "",
                       "rating": f"{R.uniform(5, 9.8):.1f}", "added": str(T0 - i * 3600), "category_id": str(ci + 1),
                       "container_extension": "mp4", "age": age, "year": str(2000 + i % 25)})
        i += 1
j = 0
for ci, (c, cnt) in enumerate(SER_CATS):
    for k in range(cnt):
        SERIES.append({"num": j + 1, "name": f"{R.choice(W1)} {R.choice(W2)} — Série {j + 1:04d}", "series_id": 5001 + j,
                       "cover": f"{HOST}/img/p{(j + 7) % 26}.jpg", "plot": f"Série {j + 1}: história de teste com várias temporadas.",
                       "genre": c, "releaseDate": f"{2005 + j % 20}-03-01", "rating": f"{R.uniform(6, 9.6):.1f}",
                       "last_modified": str(T0 - j * 1800), "backdrop_path": [f"{HOST}/img/b{j % 26}.jpg"], "category_id": str(ci + 1),
                       "youtube_trailer": ""})
        j += 1
assert len(MOVIES) == 1000 and len(SERIES) == 1000

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, body, ctype="application/json", code=200):
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def file(self, path, ctype):
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1): start = int(m.group(1))
                if m.group(2): end = int(m.group(2))
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", ctype); self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng: self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start); left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk: break
                try: self.wfile.write(chunk)
                except Exception: return
                left -= len(chunk)

    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p == "/player_api.php":
            a = q.get("action")
            if a is None:
                return self.send(json.dumps({"user_info": {"auth": 1, "status": "Active", "exp_date": str(T0 + 30 * 86400),
                    "is_trial": "0", "active_cons": "0", "max_connections": "2"}}).encode())
            if a == "get_vod_categories":
                return self.send(json.dumps([{"category_id": str(i + 1), "category_name": c, "parent_id": 0} for i, (c, _) in enumerate(VOD_CATS)]).encode())
            if a == "get_vod_streams": return self.send(json.dumps(MOVIES).encode())
            if a == "get_series_categories":
                return self.send(json.dumps([{"category_id": str(i + 1), "category_name": c, "parent_id": 0} for i, (c, _) in enumerate(SER_CATS)]).encode())
            if a == "get_series": return self.send(json.dumps(SERIES).encode())
            if a == "get_vod_info":
                m = MOVIES[int(q["vod_id"]) - 10001]
                return self.send(json.dumps({"info": {"movie_image": m["stream_icon"], "backdrop_path": [f"{HOST}/img/b{m['num'] % 26}.jpg"],
                    "plot": f"{m['name']}: sinopse completa do filme de teste, com ação, emoção e reviravoltas.",
                    "cast": "Ana Souza, Bruno Lima", "director": "Diego Alves", "genre": VOD_CATS[int(m["category_id"]) - 1][0],
                    "releasedate": f"{m['year']}-05-10", "duration_secs": 5400, "rating": m["rating"]},
                    "movie_data": {"stream_id": m["stream_id"], "name": m["name"], "container_extension": "mp4"}}).encode())
            if a == "get_series_info":
                sid = int(q["series_id"]); s = SERIES[sid - 5001]
                eps = {str(se): [{"id": str(sid * 100 + se * 10 + e), "episode_num": e, "season": se,
                                   "title": f"{s['name']} - S{se:02d}E{e:02d}", "container_extension": "mp4",
                                   "info": {"plot": f"Episódio {e} da temporada {se}.", "duration_secs": 2700}}
                                  for e in range(1, 6)] for se in (1, 2)}
                return self.send(json.dumps({"seasons": [], "info": {"name": s["name"], "cover": s["cover"], "plot": s["plot"],
                    "genre": s["genre"], "releaseDate": s["releaseDate"], "rating": s["rating"], "backdrop_path": s["backdrop_path"]},
                    "episodes": eps}).encode())
            return self.send(b"[]")
        if p == "/get.php":
            user, pw = q.get("username", "u"), q.get("password", "p")
            lines = ["#EXTM3U"]
            for cid, name, g, tvg in CHANNELS:
                logo = f' tvg-logo="{HOST}/img/l{cid % 6}.png"' if cid % 5 else ""
                lines += [f'#EXTINF:-1 tvg-id="{tvg}" tvg-name="{name}"{logo} group-title="{g}",{name}', f"{HOST}/live/{user}/{pw}/{cid}.ts"]
            return self.send("\n".join(lines).encode(), "audio/x-mpegurl")
        if p == "/xmltv.php": return self.send(xmltv(), "application/xml")
        if p.startswith("/img/"):
            f = os.path.join(ROOT, "img", os.path.basename(p))
            return self.file(f, "image/png" if f.endswith(".png") else "image/jpeg") if os.path.exists(f) else self.send(b"", code=404)
        if p.startswith("/live/"): return self.file(os.path.join(ROOT, "media/live.ts"), "video/mp2t")
        if p.startswith("/movie/") or p.startswith("/series/"): return self.file(os.path.join(ROOT, "media/vod.mp4"), "video/mp4")
        self.send(b"not found", code=404)

ThreadingHTTPServer(("0.0.0.0", 8001), H).serve_forever()
