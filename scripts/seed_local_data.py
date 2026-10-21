#!/usr/bin/env python3
"""HandsUp 로컬 시드 데이터

사용자 6명, 경매 22건(이미지 3장씩), 입찰, 북마크, 댓글, 채팅방, 거래 완료와 리뷰를 넣는다.
실제 REST API로 넣고, API로 만들 수 없는 것(카테고리·리뷰 라벨 같은 기준 데이터, 마감 후 상태)만 SQL로 보완한다.

사전 준비
  1. 인프라와 서버를 띄운다 (docker compose up -d mysql redis redpanda, ./gradlew :api:bootRun)
  2. 프론트(handsup-frontend)를 쓴다면 같은 폴더 구조(../frontend)에서 `npm run dev`로 띄운다.
     이미지는 프론트 public/seed/ 에 만들고 프론트가 서빙한다. 프론트가 없어도 DB에는 들어간다.

실행
  python3 scripts/seed_local_data.py
  환경변수: API(기본 http://localhost:8082), FE(기본 http://localhost:3004), SEED_IMAGE_DIR,
            MYSQL_CONTAINER(기본 handsup-mysql), REDIS_CONTAINER(기본 handsup-redis)
  DB가 비어 있을 때만 실행한다. 다시 넣으려면 DB를 비우거나 --force 로 실행한다.

계정: 아래 USERS의 이메일 + 비밀번호 PASSWORD (로컬 개발 전용 값)
의존: Python 3.9+, Pillow (pip install pillow). 이미지는 macOS의 한글·이모지 폰트를 쓴다.
"""
import argparse, datetime, json, os, random, subprocess, sys, urllib.error, urllib.request

API = os.environ.get("API", "http://localhost:8082")
FE = os.environ.get("FE", "http://localhost:3004")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMAGE_DIR = os.environ.get("SEED_IMAGE_DIR", os.path.normpath(os.path.join(ROOT, "..", "frontend", "public", "seed")))
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "handsup-mysql")
REDIS_CONTAINER = os.environ.get("REDIS_CONTAINER", "handsup-redis")
PASSWORD = "pass1234!"  # 로컬 개발 전용
random.seed(7)

CATEGORIES = ["디지털 기기", "가구/인테리어", "패션/잡화", "생활가전", "생활/주방", "스포츠/레저", "취미/게임/음반",
              "뷰티/미용", "반려동물용품", "티켓/교환권", "도서", "유아도서", "기타 중고 물품"]
REVIEW_LABELS = ["응답이 빨라요", "가격이 착해요", "친절하고 매너 있어요", "약속을 잘 지켜요", "설명을 잘 해줘요",
                 "물품 상태 사진과 같아요", "직접 와서 거래해요"]
CAT_COLOR = {
    "디지털 기기": ("#3b82f6", "#1e3a8a"), "가구/인테리어": ("#d97706", "#78350f"), "패션/잡화": ("#ec4899", "#831843"),
    "생활가전": ("#10b981", "#064e3b"), "생활/주방": ("#f59e0b", "#7c2d12"), "스포츠/레저": ("#22c55e", "#14532d"),
    "취미/게임/음반": ("#8b5cf6", "#4c1d95"), "뷰티/미용": ("#f472b6", "#9d174d"), "반려동물용품": ("#fb923c", "#9a3412"),
    "티켓/교환권": ("#06b6d4", "#164e63"), "도서": ("#a16207", "#422006"), "유아도서": ("#facc15", "#854d0e"),
    "기타 중고 물품": ("#64748b", "#1e293b"),
}

USERS = [  # (이메일, 닉네임, 시, 구, 동, 선호 카테고리 id)
    ("tester@naver.com", "tester", "서울시", "강남구", "역삼동", [1]),
    ("minji_seller@naver.com", "민지네중고샵", "서울시", "마포구", "합정동", [3, 8, 11]),
    ("junho_seller@naver.com", "준호", "경기도", "성남시", "정자동", [1, 7, 6]),
    ("sora_buyer@naver.com", "소라", "서울시", "서초구", "반포동", [2, 5, 4]),
    ("dohyun_buyer@naver.com", "도현", "서울시", "송파구", "잠실동", [1, 7, 10]),
    ("yuna_collector@naver.com", "유나", "서울시", "강남구", "역삼동", [8, 9, 12]),
]
TESTER, MINJI, JUNHO, SORA, DOHYUN, YUNA = range(6)

# (제목, 카테고리, 시작가, 마감까지 일수, 상태, 구매시기, 거래방식, 설명, 판매자, 이모지)
ITEMS = [
    ("아이패드 프로 11인치 3세대 256GB 와이파이", "디지털 기기", 450000, 6, "깨끗해요", "1년 이하", "직거래",
     "작년 5월에 구매해 영상 시청과 필기용으로만 썼습니다. 액정 필름과 케이스를 씌워 써서 기스 없고, 배터리 효율 94%입니다. 충전기 정품 포함.", MINJI, "📱"),
    ("소니 WH-1000XM4 노이즈캔슬링 헤드폰", "디지털 기기", 120000, 3, "깨끗해요", "1년 이상", "택배",
     "출퇴근용으로 2년 사용했고 이어패드는 작년에 새로 교체했습니다. 케이스와 3.5mm 케이블 포함, 블루투스 연결 정상.", JUNHO, "🎧"),
    ("갤럭시 버즈2 프로 미개봉", "디지털 기기", 90000, 8, "미개봉", "1개월 이하", "택배",
     "선물받았는데 이미 쓰는 이어폰이 있어 내놓습니다. 박스 개봉 전이고 영수증 있습니다.", JUNHO, "🎵"),
    ("이케아 원목 책상 140cm", "가구/인테리어", 60000, 5, "사용감 있음", "1년 이상", "직거래",
     "이사로 정리합니다. 상판에 컵 자국이 조금 있고 서랍 레일은 부드럽게 움직입니다. 직접 가져가실 분만 받습니다.", MINJI, "🪑"),
    ("무인양품 스탠드 조명", "가구/인테리어", 25000, 4, "깨끗해요", "6개월 이하", "직거래",
     "침대 옆에서 6개월 썼습니다. 전구 포함, 밝기 3단계 조절됩니다.", MINJI, "💡"),
    ("나이키 에어포스1 로우 270", "패션/잡화", 55000, 2, "깨끗해요", "3개월 이하", "택배",
     "사이즈가 안 맞아 두 번밖에 못 신었습니다. 밑창 마모 거의 없고 박스 있습니다.", MINJI, "👟"),
    ("파타고니아 레트로파일 후리스 M", "패션/잡화", 70000, 7, "깨끗해요", "1년 이하", "택배",
     "올겨울 몇 번 입었고 보풀 없습니다. 세탁 완료했습니다.", JUNHO, "🧥"),
    ("코치 미니 숄더백 블랙", "패션/잡화", 150000, 6, "깨끗해요", "1년 이하", "직거래",
     "정품이고 더스트백 포함입니다. 모서리 스크래치 없이 깨끗합니다.", YUNA, "👜"),
    ("다이슨 V8 무선청소기", "생활가전", 140000, 4, "사용감 있음", "1년 이상", "택배",
     "흡입력 좋고 배터리 1년 전 교체했습니다. 헤드 2종 포함, 거치대는 없습니다.", JUNHO, "🧹"),
    ("필립스 에어프라이어 XL", "생활가전", 45000, 3, "깨끗해요", "6개월 이하", "직거래",
     "자주 쓰지 않아 정리합니다. 바스켓 코팅 상태 좋습니다.", YUNA, "🍟"),
    ("르크루제 냄비 22cm 체리레드", "생활/주방", 130000, 9, "깨끗해요", "1년 이하", "택배",
     "결혼 선물로 받았고 3번 사용했습니다. 법랑 벗겨짐 없습니다.", MINJI, "🍲"),
    ("요가매트 + 폼롤러 세트", "스포츠/레저", 18000, 3, "깨끗해요", "3개월 이하", "직거래",
     "홈트용으로 샀다가 안 쓰게 돼서 내놓습니다. 매트 6mm, 폼롤러 45cm.", YUNA, "🧘"),
    ("캠핑 텐트 4인용 원터치", "스포츠/레저", 80000, 10, "사용감 있음", "1년 이상", "직거래",
     "가족 캠핑 3번 사용했습니다. 폴대와 팩 모두 있고 방수 상태 양호합니다.", JUNHO, "⛺"),
    ("닌텐도 스위치 OLED 화이트", "취미/게임/음반", 270000, 5, "깨끗해요", "1년 이하", "택배",
     "본체, 독, 조이콘, 충전기 풀구성입니다. 액정 필름 부착 상태 보관했습니다.", JUNHO, "🎮"),
    ("레고 테크닉 포르쉐 조립 완료", "취미/게임/음반", 200000, 12, "깨끗해요", "1년 이상", "직거래",
     "조립만 하고 진열해 둔 제품입니다. 먼지 제거했고 박스와 설명서 있습니다.", YUNA, "🧱"),
    ("아이유 LP 모음 3종", "취미/게임/음반", 90000, 6, "미개봉", "모름", "택배",
     "수집하다 중복으로 생긴 LP 3장입니다. 모두 미개봉 상태입니다.", MINJI, "💿"),
    ("조말론 잉글리쉬 페어 30ml", "뷰티/미용", 60000, 3, "깨끗해요", "6개월 이하", "택배",
     "30ml 중 3분의 2 정도 남았습니다. 박스 포함, 향이 안 맞아 판매합니다.", MINJI, "🧴"),
    ("고양이 캣타워 대형 5단", "반려동물용품", 50000, 5, "사용감 있음", "1년 이상", "직거래",
     "기스 난 곳이 조금 있지만 튼튼합니다. 해체해서 직접 가져가셔야 합니다.", JUNHO, "🐈"),
    ("스타벅스 아메리카노 기프티콘 5장", "티켓/교환권", 14000, 2, "미개봉", "1개월 이하", "택배",
     "유효기간 3개월 남았습니다. 5장 묶음으로만 판매합니다.", YUNA, "🎟️"),
    ("클린 코드 + 리팩터링 개정판", "도서", 22000, 6, "깨끗해요", "1년 이하", "택배",
     "밑줄이나 낙서 없이 깨끗합니다. 두 권 같이 팝니다.", YUNA, "📘"),
    ("프뢰벨 유아 그림책 전집 40권", "유아도서", 70000, 7, "깨끗해요", "1년 이상", "직거래",
     "아이가 커서 정리합니다. 모서리 닳음 약간, 테이프 보수 없습니다.", MINJI, "🧸"),
    ("휴대용 선풍기 + 보조배터리", "기타 중고 물품", 8000, 2, "깨끗해요", "6개월 이하", "직거래",
     "여름에 몇 번 썼습니다. 충전 케이블 포함입니다.", JUNHO, "🌬️"),
]
COMMENTS = ["상태가 어떤가요? 사진 더 볼 수 있을까요?", "택배 거래 시 배송비는 따로인가요?", "관심 있습니다! 마감 전에 입찰할게요.",
            "직거래 가능 시간대가 어떻게 되나요?", "구성품에 박스도 포함인가요?", "가격 조금 더 내려주실 수 있나요?"]


def load_env():
    env = {}
    path = os.path.join(ROOT, ".env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"')
    return env


ENV = load_env()
DB = os.environ.get("MYSQL_DATABASE", ENV.get("MYSQL_DATABASE", "handsup_dev"))


def call(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def sql(statement):
    """로컬 MySQL 컨테이너에 SQL을 실행하고 결과 텍스트를 돌려준다."""
    pw = ENV.get("MYSQL_ROOT_PASSWORD")
    if not pw:
        sys.exit(".env의 MYSQL_ROOT_PASSWORD를 읽지 못했습니다 (backend/.env 필요)")
    res = subprocess.run(["docker", "exec", "-i", MYSQL_CONTAINER, "mysql", "-uroot", f"-p{pw}",
                          "--default-character-set=utf8mb4", "-N", DB],
                         input=statement, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"SQL 실패: {res.stderr.strip()}")
    return res.stdout.strip()


# ---------- 이미지 ----------
def _font(size):
    from PIL import ImageFont
    for idx in (6, 5, 4, 0):
        try:
            return ImageFont.truetype("/System/Library/Fonts/AppleSDGothicNeo.ttc", size, index=idx)
        except Exception:
            continue
    return ImageFont.load_default()


def _gradient(size, top, bottom):
    from PIL import Image, ImageDraw
    img = Image.new("RGB", size, top)
    px = ImageDraw.Draw(img)
    t = tuple(int(top[i:i + 2], 16) for i in (1, 3, 5))
    b = tuple(int(bottom[i:i + 2], 16) for i in (1, 3, 5))
    for y in range(size[1]):
        k = y / (size[1] - 1)
        px.line([(0, y), (size[0], y)], fill=tuple(int(t[j] + (b[j] - t[j]) * k) for j in range(3)))
    return img


def _emoji(emoji, px):
    from PIL import Image, ImageDraw, ImageFont
    f = ImageFont.truetype("/System/Library/Fonts/Apple Color Emoji.ttc", 160)  # 비트맵 폰트라 160만 허용
    layer = Image.new("RGBA", (200, 200), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((100, 100), emoji, font=f, embedded_color=True, anchor="mm")
    return layer.resize((px, px), Image.LANCZOS)


def item_images(idx, title, category, emoji):
    from PIL import ImageDraw
    top, bottom = CAT_COLOR[category]
    urls = []
    for n in range(3):
        img = _gradient((600, 600), top, bottom)
        d = ImageDraw.Draw(img)
        d.ellipse((380 + n * 20, -80, 700, 240), fill=None, outline=(255, 255, 255), width=3)
        em = _emoji(emoji, 260 - n * 30)
        img.paste(em, (300 - em.width // 2, 230 - em.height // 2 + n * 10), em)
        label = ["대표 사진", "측면", "구성품"][n]
        d.rounded_rectangle((24, 24, 48 + len(label) * 28, 76), radius=22, fill=(0, 0, 0))
        d.text((40, 30), label, font=_font(30), fill=(255, 255, 255))
        d.rectangle((0, 470, 600, 600), fill=(0, 0, 0))
        d.text((300, 535), title if len(title) <= 15 else title[:15] + "…", font=_font(42), fill=(255, 255, 255), anchor="mm")
        name = f"item{idx:02d}_{n + 1}.jpg"
        img.save(os.path.join(IMAGE_DIR, name), "JPEG", quality=88)
        urls.append(f"{FE}/seed/{name}")
    return urls


def avatar(i, nick):
    from PIL import ImageDraw
    img = _gradient((200, 200), "#818cf8", "#4338ca")
    ImageDraw.Draw(img).text((100, 100), nick[0], font=_font(110), fill=(255, 255, 255), anchor="mm")
    name = f"user{i}.jpg"
    img.save(os.path.join(IMAGE_DIR, name), "JPEG", quality=90)
    return f"{FE}/seed/{name}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="이미 데이터가 있어도 실행")
    args = ap.parse_args()

    status, _ = call("POST", "/api/auth/token")
    if status == 0 or status >= 500:
        sys.exit(f"서버({API})에 연결할 수 없습니다.")
    existing = int(sql("select count(*) from auction") or 0)
    if existing and not args.force:
        sys.exit(f"이미 경매가 {existing}건 있습니다. DB를 비우거나 --force 로 다시 실행하세요.")
    os.makedirs(IMAGE_DIR, exist_ok=True)

    print("== 기준 데이터 (카테고리, 리뷰 라벨)")
    if int(sql("select count(*) from product_category") or 0) == 0:
        sql("insert into product_category(value) values " + ",".join(f"('{c}')" for c in CATEGORIES))
    if int(sql("select count(*) from review_label") or 0) == 0:
        sql("insert into review_label(review_label_value) values " + ",".join(f"('{c}')" for c in REVIEW_LABELS))

    print("== 사용자")
    tokens = []
    for i, (email, nick, si, gu, dong, cats) in enumerate(USERS):
        s, b = call("POST", "/api/users", {"email": email, "password": PASSWORD, "nickname": nick, "si": si, "gu": gu,
                                           "dong": dong, "profileImageUrl": avatar(i, nick), "productCategoryIds": cats})
        print(f"  {nick:<8} {'가입' if s == 200 else '이미 있음' if s == 400 else s}")
        s, b = call("POST", "/api/auth/login", {"email": email, "password": PASSWORD})
        if s != 200:
            sys.exit(f"로그인 실패 {email}: {b}")
        tokens.append(b["accessToken"])

    print("== 경매 (이미지 3장씩)")
    today = datetime.date.today()
    auctions = []  # (auctionId, seller, 시작가)
    for i, (title, cat, price, days, status, bought, method, desc, seller, emoji) in enumerate(ITEMS):
        u = USERS[seller]
        s, b = call("POST", "/api/auctions", {
            "title": title, "productCategory": cat, "initPrice": price,
            "endDate": str(today + datetime.timedelta(days=days)), "productStatus": status, "purchaseTime": bought,
            "description": desc, "tradeMethod": method, "imageUrls": item_images(i, title, cat, emoji),
            "si": u[2], "gu": u[3], "dong": u[4]}, token=tokens[seller])
        if s != 200:
            print("  실패", title, s, b)
            auctions.append(None)
            continue
        auctions.append((b["auctionId"], seller, price))
        print(f"  #{b['auctionId']:<3} {title[:22]:<24} {price:>8,}원  마감 D-{days}")

    print("== 입찰")
    top = {}  # auctionId -> (biddingId, bidder, price)
    placed = 0

    def bid(aid, user, price):
        nonlocal placed
        s, b = call("POST", f"/api/auctions/{aid}/bids", {"biddingPrice": price}, token=tokens[user])
        if s == 200:
            placed += 1
            top[aid] = (b["biddingId"], user, price)
        else:
            print("  입찰 실패", aid, s, b)
        return s == 200

    bidders = [TESTER, SORA, DOHYUN, YUNA]
    # 거래 흐름 시연용 최고 입찰자(헤드폰=도현, 에어포스=tester)는 그 경매에 입찰을 한 번만 한다.
    # 한 경매에 같은 사람이 여러 번 입찰하면 리뷰 작성의 입찰 조회가 2건이 되어 500이 난다(앱의 기존 제약).
    reserved = {1: DOHYUN, 5: TESTER}
    for n, a in enumerate(auctions):
        if a is None or n % 4 == 3:  # 일부 경매는 입찰 0건
            continue
        aid, seller, price = a
        pool = [u for u in bidders if u != seller and u != reserved.get(n)]
        random.shuffle(pool)
        cur = price
        for u in pool[:random.randint(1, 3)]:
            cur += random.choice([1000, 3000, 5000, 10000]) * random.randint(1, 5)
            if not bid(aid, u, cur):
                break
    # 거래 흐름 시연용: 헤드폰은 도현이, 에어포스는 tester가 최고 입찰자가 되게 한다
    headphone, airforce = auctions[1], auctions[5]
    bid(headphone[0], DOHYUN, top.get(headphone[0], (0, 0, headphone[2]))[2] + 3000)
    bid(airforce[0], TESTER, top.get(airforce[0], (0, 0, airforce[2]))[2] + 3000)
    print(f"  입찰 {placed}건")

    print("== 북마크, 댓글")
    bm = cm = 0
    for a in auctions:
        if a is None:
            continue
        aid, seller, _ = a
        others = [u for u in range(len(USERS)) if u != seller]
        for u in random.sample(others, random.randint(0, 3)):
            bm += call("POST", f"/api/auctions/bookmarks/{aid}", token=tokens[u])[0] == 200
        for u in random.sample(others, random.randint(0, 2)):
            cm += call("POST", f"/api/auctions/{aid}/comments", {"content": random.choice(COMMENTS)}, token=tokens[u])[0] == 200
    print(f"  북마크 {bm}건, 댓글 {cm}건")

    print("== 대표 이미지, 마감 후 상태")
    # API로 등록한 이미지는 대표(is_main)가 지정되지 않아 목록에서 비어 보인다. 상품의 첫 이미지를 대표로 한다.
    sql("update product_image pi join (select min(product_image_id) id from product_image group by product_id) m "
        "on pi.product_image_id = m.id set pi.is_main = 1")
    # 채팅방 등록과 거래 완료는 마감 후(TRADING)에만 가능한데 마감 스케줄러를 기다릴 수 없어 직접 바꾼다.
    ids = ",".join(str(x[0]) for x in (headphone, airforce) if x)
    sql(f"update auction set auction_status='TRADING', end_date=date_sub(curdate(), interval 1 day) where auction_id in ({ids})")

    print("== 채팅방, 거래 완료, 리뷰")
    for a, seller in ((headphone, JUNHO), (airforce, MINJI)):
        bid_id = top[a[0]][0]
        s, b = call("POST", f"/api/auctions/chat-rooms?auctionId={a[0]}&biddingId={bid_id}", token=tokens[seller])
        print(f"  채팅방(경매 #{a[0]})", s, "" if s == 200 else b)
    bid_id, buyer, _p = top[airforce[0]]
    s, b = call("PATCH", f"/api/auctions/bids/{bid_id}/complete", token=tokens[MINJI])
    print("  거래 완료", s, "" if s == 200 else b)
    if s == 200:
        s, b = call("POST", f"/api/auctions/{airforce[0]}/reviews", {
            "evaluationScore": 2, "content": "설명한 그대로였고 응답도 빨라서 거래가 편했어요.", "reviewLabelIds": [1, 3, 4]},
            token=tokens[buyer])
        print("  리뷰(구매자→판매자)", s, "" if s == 200 else b)

    # 추천 목록은 Redis에 캐시되므로 시드 전의 빈 결과가 남지 않게 지운다(서버 메모리 캐시는 5분 뒤 만료).
    subprocess.run(["docker", "exec", REDIS_CONTAINER, "sh", "-c",
                    f"redis-cli -a '{ENV.get('REDIS_HOST_PASSWORD', '')}' --no-auth-warning --scan --pattern 'recommend*' "
                    f"| xargs -r redis-cli -a '{ENV.get('REDIS_HOST_PASSWORD', '')}' --no-auth-warning del"],
                   capture_output=True)
    print("완료. 서버의 메모리 캐시가 5분 뒤 만료되면 홈 화면에 모두 보입니다.")


if __name__ == "__main__":
    main()
