"""The walkpath audit as a web page: build/audit/audit.json and its pictures -> build/audit/report.html.

usage: python3 audit_report.py [--audit DIR]
"""
import argparse
import html
import json
import os
from collections import Counter

from PIL import Image

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

PRETTY = {"castle": "Castle", "town": "Market town", "city": "City", "landmark": "Landmark"}


def shrink(src, dst):
    """A palette PNG a third the size, for publishing."""
    im = Image.open(src).convert("RGB")
    im.quantize(colors=192, method=Image.Quantize.MEDIANCUT).save(dst, optimize=True)


def summarise(r):
    c = Counter(p[0] for p in r["problems"])
    rooms = [x for x in r["rooms"] if x["size"] >= 12]
    up = [x for x in rooms if x["lvl"] >= 3]
    up_unreached = [x for x in up if x["reach"] == 0]
    return dict(rooms=len(rooms), unreachable=c.get("unreachable room", 0), half=c.get("half-reachable room", 0),
                no_way=c.get("no way in", 0), bare=c.get("bare room", 0), dark=c.get("dark room", 0),
                purpose=c.get("room with no purpose", 0), doors=len(r["doors"]), upper=len(up), upper_dead=len(up_unreached),
                lanes=r["one_wide_lanes"], ground=r["ground_cells"])


def build(audit_dir):
    data = json.load(open(os.path.join(audit_dir, "audit.json")))
    pub = os.path.join(audit_dir, "pub")
    os.makedirs(pub, exist_ok=True)
    files = {}
    order = sorted(data.values(), key=lambda r: ({"town": 0, "city": 1, "castle": 2, "landmark": 3}.get(r["kind"], 2), r["name"]))
    rows = []
    sections = []
    totals = Counter()
    for r in order:
        s = summarise(r)
        for k, v in s.items():
            totals[k] += v
        name = r["name"]
        kind = PRETTY.get(r["kind"], r["kind"])
        rows.append(f"<tr><td><a href='#{name}'>{name}</a></td><td>{kind}{' ' + str(r['tier']) if r['tier'] else ''}</td>"
                    f"<td>{s['rooms']}</td><td class='{'bad' if s['unreachable'] else ''}'>{s['unreachable']}</td>"
                    f"<td class='{'bad' if s['upper_dead'] else ''}'>{s['upper_dead']} of {s['upper']}</td>"
                    f"<td>{s['purpose']}</td><td>{s['doors']}</td><td class='{'bad' if r.get('sealed_yards') else ''}'>{', '.join(str(y[0]) for y in r.get('sealed_yards', [])[:3]) or ''}</td>"
                    f"<td>{100 * s['lanes'] // max(1, s['ground'])}%</td>"
                    f"<td>{r['walk_checked']}</td><td>{'' if not r['failures'] else 'yes'}</td></tr>")
        imgs = []
        for p in r["pics"]["iso"]:
            shrink(os.path.join(audit_dir, p), os.path.join(pub, p))
            files[f"img/{p}"] = os.path.join(pub, p)
            imgs.append(f"<figure><img src='img/{p}' alt='{name} from outside' loading='lazy'><figcaption>Outside</figcaption></figure>")
        cuts = []
        for lvl, p in r["pics"]["cut"]:
            shrink(os.path.join(audit_dir, p), os.path.join(pub, p))
            files[f"img/{p}"] = os.path.join(pub, p)
            cuts.append(f"<figure><img src='img/{p}' alt='{name} cut at floor {lvl}' loading='lazy'><figcaption>Cut away above floor +{lvl}</figcaption></figure>")
        floors = []
        fl = list(r["pics"]["floor"])
        keep = [f for f in fl if f[0] == 0] + sorted([f for f in fl if f[0] != 0 and f[2] >= 2], key=lambda f: -f[2])[:9]
        keep.sort(key=lambda f: f[0])
        for lvl, p, n in keep:
            shrink(os.path.join(audit_dir, p), os.path.join(pub, p))
            files[f"img/{p}"] = os.path.join(pub, p)
            floors.append(f"<figure><img src='img/{p}' alt='{name} floor {lvl}' loading='lazy'><figcaption>Floor +{lvl}: {n} rooms</figcaption></figure>")
        probs = Counter(p[0] for p in r["problems"])
        plist = "".join(f"<li><b>{k}</b> {v}</li>" for k, v in probs.most_common())
        detail = "".join(f"<li>{html.escape(p[0])}: {html.escape(p[1])}</li>" for p in r["problems"][:40])
        doors = "".join(f"<li>{html.escape(d[0])} at {d[1]} ({d[2]})</li>" for d in r["doors"][:12])
        kinds = r["kinds"]
        rk = ", ".join(f"{k} {v}" for k, v in sorted(kinds.items()) if k not in ("beds", "men", "lips", "machicolations", "loops", "lit", "doorways_widened", "crypt_len", "crypt_skulls", "crypt_tombs"))
        d = r["defence"]
        dline = "" if not d else f"Bowmen's posts {d['posts']}, on one-wide walks {d['one_wide']}, two-wide {d['two_wide']}; walls' foot seen {d['cover'] * 100:.0f}%, field {d['field'] * 100:.0f}%, blind posts {d['blind']}."
        fails = "".join(f"<li>{html.escape(f)}</li>" for f in r["failures"])
        sections.append(f"""
<section id='{name}' class='plan'>
<h2>{name} <span class='kind'>{kind}{' tier ' + str(r['tier']) if r['tier'] else ''}, {r['character']}</span></h2>
<p class='meta'>{r['size'][0]} by {r['size'][2]} blocks, {r['size'][1]} high. Beds {kinds.get('beds', 0)} for {kinds.get('men', 0)} men. Rooms dressed: {rk}.</p>
<p class='meta'>Walk from the gate: {r['walk_checked']} posts checked, {sum(len(v) for v in r['walk_bad'].values())} unreachable. {s['lanes']} of {s['ground']} outdoor ground cells ({100 * s['lanes'] // max(1, s['ground'])}%) are one-wide lanes. {dline}</p>
{('<p class="meta">Sealed open ground inside the walls: ' + ', '.join(f"{y[0]} cells around ({y[1]}, {y[2]})" for y in r.get('sealed_yards', [])) + '.</p>') if r.get('sealed_yards') else ''}
{('<ul class="fail">' + fails + '</ul>') if fails else ''}
<div class='gallery'>{''.join(imgs)}{''.join(cuts)}</div>
<h3>Floors</h3>
<p class='legend'><span class='sw ok'></span> floor a soldier can reach from the gate <span class='sw no'></span> floor he can't <span class='sw mk'></span> a post (knight, archer, lord, shop)</p>
<div class='gallery floors'>{''.join(floors)}</div>
<h3>Problems: {len(r['problems'])} in {s['rooms']} rooms, {s['doors']} doors</h3>
<ul class='counts'>{plist}</ul>
<details><summary>Every problem, room by room</summary><ul class='detail'>{detail}{doors}</ul></details>
</section>""")
    def group(names):
        T = Counter()
        for n in names:
            r = data[n]
            for x in r["rooms"]:
                if x["size"] < 12:
                    continue
                T["rooms"] += 1
                if x["lvl"] >= 3:
                    T["upper"] += 1
                if x["reach"] == 0:
                    T["unreach"] += 1
                    T["unreach_ground" if x["lvl"] <= 2 else "unreach_upper"] += 1
                    if x["kind"] in ("lodging", "barracks", "chamber", "quarters"):
                        T["bedrooms"] += 1
                if x["kind"] is None and x["size"] >= 20:
                    T["nop"] += 1
        return T
    towns = group([n for n in data if data[n]["kind"] == "town" or "city" in n])
    castles = group([n for n in data if data[n]["kind"] != "town" and "city" not in n])
    yards = []
    for n in ("great_castle_a", "great_castle_c", "great_castle_d", "holdfast_b"):
        if n in data and data[n].get("sealed_yards"):
            yards.append(f"{n} {', '.join(str(y[0]) for y in data[n]['sealed_yards'][:3])} cells")
    yard_text = "; ".join(yards) or "none"
    page = f"""<title>Stormhold Walkpath Audit</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Serif:wght@400;600&family=IBM+Plex+Sans:wght@400;500&family=IBM+Plex+Mono&display=swap">
<style>
/* layout: a survey ledger, one plan a section, pictures in a wrapping gallery */
:root {{ --bg:#f3efe6; --fg:#2a2520; --mute:#6b625a; --line:#d5cdbf; --panel:#faf7f0; --acc:#8a4b2a; --ok:#3f8a4a; --no:#b5433a; --mk:#2f7fb8;
  --display:'IBM Plex Serif', Georgia, serif; --body:'IBM Plex Sans', system-ui, sans-serif; --mono:'IBM Plex Mono', ui-monospace, monospace; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#1c1a17; --fg:#ece6da; --mute:#a69c8f; --line:#3a352f; --panel:#262320; --acc:#d0865e; --ok:#6cc47a; --no:#e0675c; --mk:#6fb3e6; color-scheme:dark }} }}
:root[data-theme="dark"] {{ --bg:#1c1a17; --fg:#ece6da; --mute:#a69c8f; --line:#3a352f; --panel:#262320; --acc:#d0865e; --ok:#6cc47a; --no:#e0675c; --mk:#6fb3e6; color-scheme:dark }}
body {{ background:var(--bg); color:var(--fg); font-family:var(--body); font-size:15px; line-height:1.5; padding-block:24px 64px;
  padding-left:max(16px, env(safe-area-inset-left, 0px)); padding-right:max(16px, env(safe-area-inset-right, 0px)); }}
main {{ max-width:1180px; margin:0 auto; }}
h1 {{ font-family:var(--display); font-weight:600; font-size:2rem; margin:0 0 6px; text-wrap:balance; }}
h2 {{ font-family:var(--display); font-weight:600; font-size:1.45rem; margin:0 0 4px; }}
h3 {{ font-family:var(--body); font-weight:500; font-size:1rem; margin:18px 0 6px; text-transform:uppercase; letter-spacing:.06em; color:var(--mute); }}
.lead {{ max-width:68ch; color:var(--mute); margin:0 0 20px; }}
.kind {{ font-family:var(--body); font-weight:400; font-size:.9rem; color:var(--mute); margin-left:8px; }}
.meta {{ margin:4px 0; color:var(--mute); max-width:100ch; }}
table {{ border-collapse:collapse; width:100%; font-size:.86rem; font-variant-numeric:tabular-nums; }}
th, td {{ text-align:left; padding:5px 8px; border-bottom:1px solid var(--line); white-space:nowrap; }}
th {{ font-weight:500; color:var(--mute); font-size:.78rem; text-transform:uppercase; letter-spacing:.05em; }}
td.bad {{ color:var(--no); font-weight:500; }}
.wrap {{ overflow-x:auto; margin:0 0 28px; border:1px solid var(--line); background:var(--panel); }}
.findings {{ background:var(--panel); border-left:4px solid var(--acc); padding:12px 16px; margin:0 0 28px; max-width:80ch; }}
.findings li {{ margin:6px 0; }}
section.plan {{ border-top:1px solid var(--line); padding-top:20px; margin-top:28px; }}
.gallery {{ display:flex; flex-wrap:wrap; gap:12px; }}
figure {{ margin:0; flex:1 1 340px; min-width:0; max-width:560px; }}
.floors figure {{ flex:1 1 420px; max-width:620px; }}
figure img {{ width:100%; height:auto; border:1px solid var(--line); background:#d4dde8; image-rendering:pixelated; }}
figcaption {{ font-size:.8rem; color:var(--mute); margin-top:4px; }}
.legend {{ font-size:.82rem; color:var(--mute); }}
.sw {{ display:inline-block; width:12px; height:12px; vertical-align:-1px; margin:0 4px 0 10px; border:1px solid var(--line); }}
.sw.ok {{ background:#3c9a3c }} .sw.no {{ background:#b03a3a }} .sw.mk {{ background:transparent; border:2px solid var(--mk); }}
ul.counts {{ display:flex; flex-wrap:wrap; gap:6px 18px; padding:0; list-style:none; margin:0 0 8px; }}
ul.fail li {{ color:var(--no); }}
details {{ font-size:.84rem; }} details summary {{ cursor:pointer; color:var(--acc); }}
ul.detail {{ columns:2; column-gap:24px; font-family:var(--mono); font-size:.76rem; }}
ul.detail li {{ break-inside:avoid; }}
@media (max-width:700px) {{ ul.detail {{ columns:1 }} }}
a {{ color:var(--acc); }}
</style>
<main>
<h1>Stormhold Walkpath Audit</h1>
<p class='lead'>Every plan the generator builds, as it reaches the world: walked from the gate by the same rules the soldiers' pathfinder uses, every room checked for a way in, a purpose, furniture and light, every door for a wall behind it, every market lane for width. Pictures are the generator's own output, cut open floor by floor.</p>
<div class='findings'>
<h3>What the audit found</h3>
<ul>
<li><b>Upstairs is sealed in nearly every town and city house.</b> {towns['unreach_upper']} of {towns['upper']} upper rooms in the towns and cities have no stair or ladder from the floor below: the townhouse generator lays plank floors between storeys and never cuts a way up. {towns['bedrooms']} of those are lodgings with beds nobody can sleep in. The castles have the same fault in {castles['unreach_upper']} upper rooms, mostly tower floors.</li>
<li><b>Sealed wards and tower rooms in the castles.</b> {castles['unreach_ground']} ground rooms in the castles have no opening at all, most of them the ground floors of the corner and wall towers (kennels, cellars, wells, stores). The great castles also wall off whole yards: {yard_text}.</li>
<li><b>Market stalls are a solid block.</b> A stall is four fence posts with a square of wool and a carpet on top, packed on a 4-block grid so the stalls touch: no lane a villager can trade through, every stall the same shape, no counter you can see from the street. A rebuilt stall needs a pitched awning, an open front, a counter and goods on it, and a two-wide lane between rows.</li>
<li><b>{totals['purpose']} rooms of twenty cells or more given no purpose</b> ({towns['nop']} in the towns and cities: upper house floors, arcade bays and tower tops; {castles['nop']} in the castles: tower tops under their caps and the stair halls). They're built, lit, and empty.</li>
<li><b>{totals['doors']} house doors hung against a party wall</b> in the cities and towns: a terrace house's door set where the next house's wall stands. No castle has one.</li>
<li><b>Lanes.</b> {100 * totals['lanes'] // max(1, totals['ground'])}% of outdoor ground cells are one-wide corridors overall; the worst are the great castles' wall-walk passages (11%) and the temple ruin's fallen drums.</li>
</ul>
</div>
<h3>All plans</h3>
<div class='wrap'><table>
<tr><th>Plan</th><th>Kind</th><th>Rooms</th><th>Unreachable</th><th>Upper rooms sealed</th><th>No purpose</th><th>Bad doors</th><th>Sealed yards</th><th>One-wide lanes</th><th>Posts</th><th>Fails build</th></tr>
{''.join(rows)}
</table></div>
{''.join(sections)}
</main>
"""
    out = os.path.join(audit_dir, "report.html")
    open(out, "w").write(page)
    json.dump(files, open(os.path.join(audit_dir, "files.json"), "w"))
    print(out, len(files), "images", sum(os.path.getsize(v) for v in files.values()) // 1024, "KB")
    return out, files


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--audit", default=os.path.join(ROOT, "build", "audit"))
    build(ap.parse_args().audit)
