import os, re, glob

base = 'V2rayNG/app/src/main/res'
defined = set()

for f in glob.glob(os.path.join(base, 'values', '*.xml')):
    try:
        content = open(f, encoding='utf-8', errors='ignore').read()
        for m in re.findall(r'name="([^"]+)"', content):
            defined.add(('style', m))
    except: pass

for f in glob.glob(os.path.join(base, 'values', 'colors.xml')):
    try:
        content = open(f, encoding='utf-8', errors='ignore').read()
        for m in re.findall(r'name="([^"]+)"', content):
            defined.add(('color', m))
    except: pass

for f in glob.glob(os.path.join(base, 'values', 'dimens.xml')):
    try:
        content = open(f, encoding='utf-8', errors='ignore').read()
        for m in re.findall(r'name="([^"]+)"', content):
            defined.add(('dimen', m))
    except: pass

for f in glob.glob(os.path.join(base, 'values', 'strings.xml')):
    try:
        content = open(f, encoding='utf-8', errors='ignore').read()
        for m in re.findall(r'name="([^"]+)"', content):
            defined.add(('string', m))
    except: pass

for f in glob.glob(os.path.join(base, 'drawable', '*.xml')):
    name = os.path.splitext(os.path.basename(f))[0]
    defined.add(('drawable', name))

for f in glob.glob(os.path.join(base, 'color', '*.xml')):
    name = os.path.splitext(os.path.basename(f))[0]
    defined.add(('color', name))

print(f'Defined: {len(defined)} resources')

referenced = []
for d in [os.path.join(base, 'layout'), os.path.join(base, 'drawable'), os.path.join(base, 'menu'), os.path.join(base, 'color')]:
    if not os.path.isdir(d):
        continue
    for f in glob.glob(os.path.join(d, '**', '*.xml'), recursive=True):
        try:
            content = open(f, encoding='utf-8', errors='ignore').read()
            for m in re.findall(r'@(\w+)/([\w.]+)', content):
                referenced.append((m[0], m[1], f))
        except: pass

print(f'References: {len(referenced)}')

missing = []
seen = set()
for rtype, rname, rfile in referenced:
    key = (rtype, rname)
    if key in seen:
        continue
    if rtype in ('color', 'dimen', 'drawable', 'string', 'mipmap'):
        if key not in defined:
            missing.append((rtype, rname, os.path.basename(rfile)))
            seen.add(key)
    elif rtype == 'style':
        if key not in defined:
            missing.append((rtype, rname, os.path.basename(rfile)))
            seen.add(key)

if missing:
    print(f'\n=== MISSING {len(missing)} RESOURCES ===')
    for rtype, rname, rfile in sorted(missing):
        print(f'  @{rtype}/{rname}  <- {rfile}')
else:
    print('\nAll resource references OK!')
