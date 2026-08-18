import glob, os, re, sys
import xml.dom.minidom

errors, warns = [], []
RES = "app/src/main/res"
xmls = sorted(glob.glob(f"{RES}/**/*.xml", recursive=True)) + ["app/src/main/AndroidManifest.xml"]

# 1. XML well-formed + "--" trong comment
for f in xmls:
    raw = open(f, encoding="utf-8").read()
    try:
        xml.dom.minidom.parseString(raw)
    except Exception as e:
        errors.append(f"{f}: XML khong hop le -> {e}")
    for m in re.finditer(r"<!--(.*?)-->", raw, re.S):
        if "--" in m.group(1):
            ln = raw[:m.start()].count("\n") + 1
            errors.append(f'{f}:{ln}: comment chua "--" (XML cam)')

# 2. Gia tri thuoc tinh bat dau bang @ hoac ? phai la tham chieu hop le
VALID_REF = re.compile(r"^[@?]\+?(android:)?\w+/[\w.]+$")
for f in xmls:
    raw = open(f, encoding="utf-8").read()
    for m in re.finditer(r'(\w+:[\w]+)="([@?][^"]*)"', raw):
        attr, val = m.group(1), m.group(2)
        if val.startswith("\\"):
            continue
        if not VALID_REF.match(val):
            ln = raw[:m.start()].count("\n") + 1
            errors.append(f'{f}:{ln}: {attr}="{val}" -> bat dau bang @/? nhung khong phai tham chieu resource (can escape \\@)')

# 3. Moi @drawable/@string/@style/@mipmap tham chieu deu phai ton tai
defined = set()
for f in glob.glob(f"{RES}/**/*.xml", recursive=True):
    kind = os.path.basename(os.path.dirname(f)).split("-")[0]
    if kind in ("drawable", "layout", "mipmap"):
        defined.add(f"{kind}/{os.path.splitext(os.path.basename(f))[0]}")
for f in glob.glob(f"{RES}/mipmap-*/*.png"):
    defined.add(f"mipmap/{os.path.splitext(os.path.basename(f))[0]}")
for f in glob.glob(f"{RES}/values/*.xml"):
    raw = open(f, encoding="utf-8").read()
    for m in re.finditer(r'<(string|style|color|dimen)\s+name="([\w.]+)"', raw):
        defined.add(f"{m.group(1)}/{m.group(2)}")

for f in xmls:
    raw = open(f, encoding="utf-8").read()
    for m in re.finditer(r'"@(drawable|string|style|mipmap|color|dimen|layout)/([\w.]+)"', raw):
        ref = f"{m.group(1)}/{m.group(2)}"
        if ref not in defined:
            ln = raw[:m.start()].count("\n") + 1
            errors.append(f"{f}:{ln}: tham chieu @{ref} khong ton tai")

# 4. R.id / R.layout dung trong Kotlin phai co trong layout
ids = set()
for f in glob.glob(f"{RES}/layout/*.xml"):
    ids |= set(re.findall(r'android:id="@\+id/(\w+)"', open(f, encoding="utf-8").read()))
layouts = {os.path.splitext(os.path.basename(f))[0] for f in glob.glob(f"{RES}/layout/*.xml")}

for f in glob.glob("app/src/main/java/**/*.kt", recursive=True):
    raw = open(f, encoding="utf-8").read()
    for m in re.finditer(r"R\.id\.(\w+)", raw):
        if m.group(1) not in ids:
            errors.append(f"{f}: dung R.id.{m.group(1)} nhung khong co trong layout nao")
    for m in re.finditer(r"R\.layout\.(\w+)", raw):
        if m.group(1) not in layouts:
            errors.append(f"{f}: dung R.layout.{m.group(1)} nhung file layout khong ton tai")
    o, c = raw.count("{"), raw.count("}")
    if o != c:
        errors.append(f"{f}: ngoac nhon lech {{{o}}} vs }}{c}}}")

# 5. Activity khai bao trong manifest phai co file .kt
mf = open("app/src/main/AndroidManifest.xml", encoding="utf-8").read()
for m in re.finditer(r'android:name="\.(\w+)"', mf):
    if not os.path.exists(f"app/src/main/java/com/squarehub/android/{m.group(1)}.kt"):
        errors.append(f"AndroidManifest.xml: khai bao .{m.group(1)} nhung khong co file {m.group(1)}.kt")

print(f"Da kiem tra {len(xmls)} file XML + {len(glob.glob('app/src/main/java/**/*.kt', recursive=True))} file Kotlin\n")
for e in errors: print("LOI  :", e)
for w in warns: print("CANH BAO:", w)
print()
print("KET QUA:", "KHONG PHAT HIEN LOI" if not errors else f"{len(errors)} LOI CAN SUA")
sys.exit(1 if errors else 0)
