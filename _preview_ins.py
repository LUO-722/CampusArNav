# Ins 风格配色预演图：登录 / 课程表 / 我的 三屏拼图（仅示意，实际以安装为准）
from PIL import Image, ImageDraw, ImageFont

W, H = 390, 844
GAP = 24
BG = (255, 247, 251)
CARD = (255, 255, 255)
ACCENT = (232, 68, 122)
ACCENT_SOFT = (255, 227, 238)
BLUE = (127, 196, 232)
BLUE_SOFT = (234, 246, 253)
BLUE_INK = (46, 127, 168)
BORDER = (244, 222, 232)
T1 = (61, 51, 64)
T2 = (156, 138, 163)

f_reg = "C:/Windows/Fonts/msyh.ttc"
f_bold = "C:/Windows/Fonts/msyhbd.ttc"


def F(size, bold=False):
    return ImageFont.truetype(f_bold if bold else f_reg, size)


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    top, bot = (255, 241, 247), (237, 247, 255)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    return img


def rrect(d, box, r, fill, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def text(d, xy, s, font, fill, anchor="la"):
    d.text(xy, s, font=font, fill=fill, anchor=anchor)


def panel_login():
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    text(d, (24, 96), "科文智嘉", F(30, True), T1)
    text(d, (24, 142), "登录教务系统 · 学号 + 密码", F(13), T2)

    rrect(d, (16, 176, W - 16, 372), 20, CARD, BORDER)
    text(d, (36, 196), "学号", F(11), T2)
    rrect(d, (36, 216, W - 36, 262), 12, CARD, BORDER)
    text(d, (48, 229), "9926****25", F(14), T1)
    text(d, (36, 276), "密码", F(11), T2)
    rrect(d, (36, 296, W - 36, 342), 12, CARD, BORDER)
    text(d, (48, 309), "••••••••", F(14), T1)

    rrect(d, (16, 388, W - 16, 566), 20, CARD, BORDER)
    text(d, (36, 408), "登录网络", F(16, True), T1)
    text(d, (36, 436), "内网：全天可登录（需连校园网）。外网：开放时段", F(11), T2)
    text(d, (36, 454), "09:00—18:00，时段外可试但可能不通。", F(11), T2)
    rrect(d, (36, 480, 176, 524), 14, ACCENT_SOFT, ACCENT, 2)
    text(d, (106, 502), "内网 · 全天", F(12), ACCENT, "mm")
    rrect(d, (188, 480, W - 36, 524), 14, CARD, BORDER)
    text(d, ((188 + W - 36) / 2, 502), "外网 · 9:00—18:00", F(12), T2, "mm")
    text(d, (36, 540), "现在 13:39，外网开放时段内，可以直接登录。", F(11), ACCENT)

    rrect(d, (16, 586, W - 16, 634), 14, ACCENT, None)
    text(d, (W / 2, 610), "登录并同步", F(15, True), (255, 255, 255), "mm")
    rrect(d, (16, 646, W - 16, 694), 14, CARD, BORDER)
    text(d, (W / 2, 670), "先离线使用（不同步课表）", F(13), T2, "mm")
    text(d, (24, 730), "· 学号密码只存在本机，密码用 AndroidKeyStore 加密", F(10), T2)
    text(d, (24, 748), "· 登录成功后课表 / 考试 / 成绩原文存 App 私有目录", F(10), T2)
    text(d, (24, 766), "· 外网入口开放 09:00—18:00，已缓存内容随时可看", F(10), T2)
    return img


def bottom_nav(d, active=0):
    top = H - 74
    rrect(d, (GRID_L, top, GRID_L, top), 0, CARD)
    rrect(d, (0, top, W, H), 20, CARD, BORDER)
    d.rectangle([0, top, W, top + 20], fill=CARD)
    labels = [("▦", "课程表"), ("✦", "智嘉"), ("➤", "导航"), ("✉", "对话"), ("☺", "我的")]
    step = W / 5
    for i, (icon, name) in enumerate(labels):
        cx = step * i + step / 2
        on = i == active
        if on:
            rrect(d, (cx - 30, top + 8, cx + 30, H - 10), 14, ACCENT_SOFT)
        text(d, (cx, top + 26), icon, F(15), ACCENT if on else T2, "mm")
        text(d, (cx, top + 48), name, F(10), ACCENT if on else T2, "mm")


GRID_L = 16


def panel_timetable():
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    text(d, (24, 74), "课程表", F(26, True), T1)
    text(d, (24, 112), "2026—2027 学年第一学期 · 19 周 · 本周第 12 周 · 科文作息", F(11), T2)
    rrect(d, (24, 134, 174, 166), 12, ACCENT_SOFT)
    text(d, (99, 150), "第 12 周  ▾", F(12), ACCENT, "mm")

    gx0, gx1 = GRID_L, W - GRID_L
    gy0, gy1 = 182, 508
    rrect(d, (gx0, gy0, gx1, gy1), 18, CARD, BORDER)
    label_w = 34
    cw = (gx1 - gx0 - label_w) / 7
    days = ["一", "二", "三", "四", "五", "六", "日"]
    text(d, (gx0 + label_w / 2, gy0 + 16), "节", F(9), T2, "mm")
    for i, day in enumerate(days):
        text(d, (gx0 + label_w + cw * i + cw / 2, gy0 + 16), day, F(10), T2, "mm")
    rows = [("1", "08:15"), ("2", "09:00"), ("3", "09:55"), ("4", "10:40"), ("5", "11:25"), ("6", "13:50")]
    rh = (gy1 - gy0 - 30) / 6
    for r, (no, tm) in enumerate(rows):
        y = gy0 + 30 + rh * r
        text(d, (gx0 + label_w / 2, y + rh / 2 - 6), no, F(9), T2, "mm")
        text(d, (gx0 + label_w / 2, y + rh / 2 + 6), tm, F(7), (185, 168, 192), "mm")
        d.line([(gx0 + label_w, y), (gx1 - 8, y)], fill=BORDER, width=1)

    blocks = [
        (3, 0, 1, "高数ⅠA", (248, 174, 203)),
        (3, 1, 1, "英语A", (168, 216, 242)),
        (4, 0, 1, "Java", (201, 188, 236)),
        (2, 3, 1, "Web", (169, 227, 201)),
    ]
    for col, row, span, name, color in blocks:
        x0 = gx0 + label_w + cw * col + 2
        y0 = gy0 + 30 + rh * row + 2
        rrect(d, (x0, y0, x0 + cw - 4, y0 + rh - 4), 8, color)
        text(d, (x0 + 5, y0 + 6), name, F(8, True), (61, 51, 64))

    # 活动块（虚线）
    x0 = gx0 + label_w + cw * 4 + 2
    y0 = gy0 + 30 + rh * 5 + 2
    rrect(d, (x0, y0, x0 + cw - 4, y0 + rh - 4), 8, (255, 217, 233))
    d.rounded_rectangle([x0, y0, x0 + cw - 4, y0 + rh - 4], radius=8, outline=ACCENT, width=1)
    text(d, (x0 + 5, y0 + 5), "社团活动", F(7, True), (61, 51, 64))
    text(d, (x0 + 5, y0 + rh - 14), "19:00", F(6), T2)

    # 今日 / 即将
    rrect(d, (GRID_L, 522, W - GRID_L, 596), 16, ACCENT_SOFT)
    text(d, (32, 534), "今天 · 星期四", F(11), ACCENT)
    text(d, (32, 556), "08:15-09:55  高等数学ⅠA · 致广楼-303", F(11), T1)
    text(d, (32, 576), "13:50-15:15  Web前端设计与开发 · 匠心楼201", F(11), T1)
    rrect(d, (GRID_L, 608, W - GRID_L, 682), 16, BLUE_SOFT)
    text(d, (32, 620), "接下来", F(11), BLUE_INK)
    text(d, (32, 642), "16:15-17:40  Java · 匠心楼201 · 石玲", F(11), T1)
    text(d, (32, 664), "19:00  社团活动（活动已录进课表）", F(11), T1)

    # 工具栏
    rrect(d, (GRID_L, 696, 110, 742), 12, CARD, BORDER)
    text(d, (63, 719), "导入课表", F(11), ACCENT, "mm")
    rrect(d, (118, 696, 200, 742), 12, CARD, BORDER)
    text(d, (159, 719), "＋活动", F(11), ACCENT, "mm")
    rrect(d, (208, 696, 296, 742), 12, CARD, BORDER)
    text(d, (252, 719), "导出分享", F(11), ACCENT, "mm")
    bottom_nav(d, 0)
    return img


def panel_profile():
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    text(d, (24, 74), "我的", F(26, True), T1)
    text(d, (24, 112), "2026—2027 学年第一学期 · 第 12 / 19 周", F(11), T2)

    rrect(d, (GRID_L, 134, W - GRID_L, 318), 20, CARD, BORDER)
    d.ellipse([36, 156, 96, 216], fill=ACCENT_SOFT, outline=ACCENT, width=2)
    text(d, (66, 186), "罗", F(22, True), ACCENT, "mm")
    text(d, (110, 164), "罗同学", F(20, True), T1)
    text(d, (110, 196), "人工智能与软件学院 · 软件工程 · 26软件2", F(10), T2)
    rrect(d, (W - 96, 160, W - 36, 192), 12, BLUE_SOFT)
    text(d, (W - 66, 176), "编辑", F(11), BLUE_INK, "mm")
    pairs = [("学号", "9926****25", "性别", "男"), ("院系", "人工智能与软件学院", "专业", "软件工程"),
             ("班级", "26软件2", "学制", "4 年"), ("入学", "2026-09-13", "宿舍", "—")]
    y = 226
    for l1, v1, l2, v2 in pairs:
        text(d, (36, y), l1, F(9), T2)
        text(d, (96, y), v1, F(11), T1)
        text(d, (216, y), l2, F(9), T2)
        text(d, (266, y), v2, F(11), T1)
        y += 24

    # 统计格
    stats = [("10", "课程", True), ("10", "本周", False), ("0", "考试", True), ("0", "成绩", False)]
    bw = (W - 2 * GRID_L - 24) / 4
    for i, (val, lab, pink) in enumerate(stats):
        x = GRID_L + i * (bw + 8)
        rrect(d, (x, 334, x + bw, 398), 16, ACCENT_SOFT if pink else BLUE_SOFT)
        text(d, (x + bw / 2, 356), val, F(19, True), ACCENT if pink else BLUE_INK, "mm")
        text(d, (x + bw / 2, 382), lab, F(10), T2, "mm")

    # 本周课程
    rrect(d, (GRID_L, 414, W - GRID_L, 630), 20, CARD, BORDER)
    text(d, (32, 428), "本周课程 · 按星期", F(14, True), T1)
    text(d, (32, 454), "星期一", F(11), ACCENT)
    lines = [("08:15-09:55", "高等数学ⅠA", "致广楼-303 · 车素兵"),
             ("13:50-15:15", "大学英语A", "致广楼-109 · 秦思"),
             ("16:15-17:40", "面向对象程序设计Ⅰ(Java)", "致广楼-301 · 石玲")]
    y = 474
    for tm, name, sub in lines:
        text(d, (32, y), tm, F(9), T2)
        text(d, (110, y - 2), name, F(11), T1)
        text(d, (110, y + 14), sub, F(9), T2)
        y += 40
    text(d, (32, 604), "星期二 · 3 门", F(11), ACCENT)

    # 教务数据
    rrect(d, (GRID_L, 644, W - GRID_L, 700), 16, BLUE_SOFT)
    text(d, (32, 656), "教务数据 · 外网 9:00—18:00 · 最近同步 12:26", F(10), BLUE_INK)
    text(d, (32, 676), "考试安排 0 条（期末安排一般到学期中后段才发布）", F(10), T1)
    bottom_nav(d, 4)
    return img


panels = [panel_login(), panel_timetable(), panel_profile()]
out = Image.new("RGB", (W * 3 + GAP * 4, H + GAP * 2), (250, 240, 246))
for i, p in enumerate(panels):
    out.paste(p, (GAP + i * (W + GAP), GAP))
out.save(r"E:\AndroidProjects\CampusArNav\预览-Ins配色-0.9.0.png")
print("saved", out.size)