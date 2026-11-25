package ru.unogame;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/** Aturan permainan Uno klasik + mode "System Juara". Tidak ada kode UI di sini. */
public class UnoEngine {

    public static final int RED = 0, YEL = 1, GRN = 2, BLU = 3, WILD = 4;
    public static final int SKIP = 10, REV = 11, D2 = 12, W = 13, W4 = 14;
    public static final int EV_START = 0, EV_PLAY = 1, EV_DRAW = 2, EV_PASS = 3,
            EV_UNO = 4, EV_WIN = 5, EV_SKIP = 6, EV_REV = 7, EV_FINISH = 8;

    public static int col(int c) { return c / 20; }
    public static int val(int c) { return c % 20; }
    public static int mk(int col, int v) { return col * 20 + v; }

    public static class P {
        public String id = "", name = "";
        public boolean bot;
        /** Kursi 0..9 di meja. Urutan giliran = urutan kursi searah jarum. */
        public int seat = -1;
        /** Penampilan 3D: topi (0..5), wajah (0..5), warna aksesoris (0..5). */
        public int hat, face, col;
        /** Warna custom dari color picker (ARGB). 0 = pakai palet bawaan (col / index pemain). */
        public int acc, body, skin;
        public ArrayList<Integer> hand = new ArrayList<Integer>();
    }

    public ArrayList<P> players = new ArrayList<P>();
    public ArrayList<Integer> draw = new ArrayList<Integer>();
    public ArrayList<Integer> disc = new ArrayList<Integer>();
    public int turn, dir = 1, color, winner = -1, drawnCard = -1, seq;
    public boolean over;
    /**
     * System Juara: kalau aktif, pemain yang habis kartunya dapat peringkat (1, 2, 3, ...)
     * dan keluar dari giliran (menonton). Game selesai saat tinggal satu pemain.
     */
    public boolean juara;
    /** Urutan selesai: indeks pemain, index 0 = juara 1. */
    public ArrayList<Integer> ranks = new ArrayList<Integer>();
    /** ID penulisan terakhir, untuk menyelesaikan tabrakan tulis di multiplayer. */
    public String wid = "";
    /** Event dari aksi terakhir: {tipe, pemain, kartu/peringkat, jumlah/warna}. */
    public ArrayList<int[]> evs = new ArrayList<int[]>();
    private final Random rnd = new Random();

    private void ev(int type, int p, int c, int n) { evs.add(new int[]{type, p, c, n}); }

    public boolean finished(int pi) { return ranks.contains(pi); }

    /** Pemain ke-n searah putaran; pemain yang sudah selesai dilewati. */
    public int step(int n) {
        int s = players.size();
        if (ranks.size() >= s) return turn;
        int idx = turn;
        int moved = 0;
        while (moved < n) {
            idx = ((idx + dir) % s + s) % s;
            if (!finished(idx)) moved++;
        }
        return idx;
    }

    public int top() { return disc.get(disc.size() - 1); }

    /**
     * Pindah ke kursi kosong. Urutan pemain diurutkan ulang menurut kursi, lalu indeks giliran,
     * peringkat, dan pemenang dipetakan ulang ke pemain yang sama. Mengembalikan indeks baru pemain, -1 jika gagal.
     */
    public int moveSeat(int pi, int newSeat) {
        if (over || pi < 0 || pi >= players.size() || newSeat < 0 || newSeat > 9) return -1;
        for (P p : players) if (p.seat == newSeat) return -1;
        P me = players.get(pi);
        P turnP = turn < players.size() ? players.get(turn) : null;
        P winP = winner >= 0 && winner < players.size() ? players.get(winner) : null;
        ArrayList<P> rankP = new ArrayList<P>();
        for (int r : ranks) rankP.add(players.get(r));
        me.seat = newSeat;
        Collections.sort(players, new Comparator<P>() {
            public int compare(P a, P b) { return a.seat - b.seat; }
        });
        if (turnP != null) turn = players.indexOf(turnP);
        if (winP != null) winner = players.indexOf(winP);
        ranks = new ArrayList<Integer>();
        for (P r : rankP) ranks.add(players.indexOf(r));
        evs.clear();
        seq++;
        return players.indexOf(me);
    }

    /**
     * Tukar kursi dua pemain. Urutan pemain diurutkan ulang menurut kursi, lalu giliran, peringkat,
     * dan pemenang dipetakan ulang. Mengembalikan indeks baru pemain pa, -1 jika gagal.
     */
    public int swapSeats(int pa, int pb) {
        if (over || pa == pb || pa < 0 || pb < 0 || pa >= players.size() || pb >= players.size()) return -1;
        P a = players.get(pa), b = players.get(pb);
        P turnP = turn < players.size() ? players.get(turn) : null;
        P winP = winner >= 0 && winner < players.size() ? players.get(winner) : null;
        ArrayList<P> rankP = new ArrayList<P>();
        for (int r : ranks) rankP.add(players.get(r));
        int tmp = a.seat;
        a.seat = b.seat;
        b.seat = tmp;
        Collections.sort(players, new Comparator<P>() {
            public int compare(P x, P y) { return x.seat - y.seat; }
        });
        if (turnP != null) turn = players.indexOf(turnP);
        if (winP != null) winner = players.indexOf(winP);
        ranks = new ArrayList<Integer>();
        for (P r : rankP) ranks.add(players.indexOf(r));
        evs.clear();
        seq++;
        return players.indexOf(a);
    }

    public int indexOf(String id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- mulai
    public void newGame(ArrayList<P> ps) {
        players = ps;
        draw = new ArrayList<Integer>();
        for (int c = 0; c < 4; c++) {
            draw.add(mk(c, 0));
            for (int v = 1; v <= 12; v++) { draw.add(mk(c, v)); draw.add(mk(c, v)); }
        }
        for (int i = 0; i < 4; i++) { draw.add(mk(WILD, W)); draw.add(mk(WILD, W4)); }
        Collections.shuffle(draw, rnd);
        disc = new ArrayList<Integer>();
        for (P p : players) {
            p.hand = new ArrayList<Integer>();
            for (int i = 0; i < 7; i++) p.hand.add(draw.remove(draw.size() - 1));
            Collections.sort(p.hand);
        }
        int idx = draw.size() - 1;
        while (val(draw.get(idx)) > 9) idx--;
        int first = draw.remove(idx);
        disc.add(first);
        color = col(first);
        dir = 1; over = false; winner = -1; drawnCard = -1;
        ranks = new ArrayList<Integer>();
        for (int i = 0; i < players.size(); i++) if (players.get(i).seat < 0) players.get(i).seat = (i * 10) / players.size();
        Collections.sort(players, new Comparator<P>() {
            public int compare(P a, P b) { return a.seat - b.seat; }
        });
        turn = rnd.nextInt(players.size());
        evs.clear();
        ev(EV_START, 0, first, 0);
        seq++;
    }

    // ---------------------------------------------------------------- aturan
    private int takeCard() {
        if (draw.isEmpty()) {
            if (disc.size() <= 1) return -1;
            int top = disc.remove(disc.size() - 1);
            draw.addAll(disc);
            disc.clear();
            disc.add(top);
            Collections.shuffle(draw, rnd);
        }
        if (draw.isEmpty()) return -1;
        return draw.remove(draw.size() - 1);
    }

    private boolean matches(int card) {
        int c = col(card);
        if (c == WILD) return true;
        int t = top();
        return c == color || (col(t) != WILD && val(t) == val(card));
    }

    public boolean canPlay(int pi, int card) {
        if (over || pi != turn) return false;
        if (drawnCard >= 0 && card != drawnCard) return false;
        return matches(card);
    }

    public boolean hasPlayable(int pi) {
        for (int c : players.get(pi).hand) if (canPlay(pi, c)) return true;
        return false;
    }

    private void giveCards(int pi, int n) {
        P p = players.get(pi);
        int got = 0;
        for (int i = 0; i < n; i++) {
            int c = takeCard();
            if (c < 0) break;
            p.hand.add(c);
            got++;
        }
        Collections.sort(p.hand);
        ev(EV_DRAW, pi, 0, got);
    }

    public void play(int pi, int card, int chosen) {
        evs.clear();
        P p = players.get(pi);
        p.hand.remove(Integer.valueOf(card));
        disc.add(card);
        drawnCard = -1;
        int c = col(card);
        color = (c == WILD) ? chosen : c;
        ev(EV_PLAY, pi, card, color);

        if (p.hand.isEmpty()) {
            ranks.add(pi);
            int place = ranks.size();
            if (!juara || place >= players.size() - 1) {
                finishGame();
                seq++;
                return;
            }
            ev(EV_FINISH, pi, place, 0); // mode juara: pemain keluar, permainan lanjut
        } else if (p.hand.size() == 1) {
            ev(EV_UNO, pi, 0, 0); // UNO otomatis, seperti aturan klasik
        }

        int v = val(card);
        if (v == SKIP) {
            ev(EV_SKIP, step(1), 0, 0);
            turn = step(2);
        } else if (v == REV) {
            dir = -dir;
            ev(EV_REV, pi, 0, 0);
            turn = (players.size() - ranks.size() == 2) ? step(2) : step(1);
        } else if (v == D2) {
            int vi = step(1);
            giveCards(vi, 2);
            turn = step(2);
        } else if (v == W4) {
            int vi = step(1);
            giveCards(vi, 4);
            turn = step(2);
        } else {
            turn = step(1);
        }
        seq++;
    }

    /** Akhir permainan. Mode juara: sisa pemain masuk urutan terakhir berdasarkan kartu tersisa. */
    private void finishGame() {
        if (juara) {
            ArrayList<Integer> rest = new ArrayList<Integer>();
            for (int i = 0; i < players.size(); i++) if (!finished(i)) rest.add(i);
            Collections.sort(rest, new Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    return players.get(a).hand.size() - players.get(b).hand.size();
                }
            });
            ranks.addAll(rest);
        }
        over = true;
        winner = ranks.get(0);
        ev(EV_WIN, winner, 0, 0);
    }

    /** Ambil satu kartu dari dek. Kalau bisa dimainkan, giliran tetap; kalau tidak, lewat. */
    public void drawTurn(int pi) {
        evs.clear();
        drawnCard = -1;
        int c = takeCard();
        if (c < 0) {
            ev(EV_PASS, pi, 0, 0);
            turn = step(1);
            seq++;
            return;
        }
        P p = players.get(pi);
        p.hand.add(c);
        Collections.sort(p.hand);
        ev(EV_DRAW, pi, 0, 1);
        if (matches(c)) {
            drawnCard = c;
        } else {
            ev(EV_PASS, pi, 0, 0);
            turn = step(1);
        }
        seq++;
    }

    public void pass(int pi) {
        evs.clear();
        drawnCard = -1;
        ev(EV_PASS, pi, 0, 0);
        turn = step(1);
        seq++;
    }

    // ---------------------------------------------------------------- bot
    public int botChoose(int pi) {
        P p = players.get(pi);
        int nextSize = players.get(step(1)).hand.size();
        int best = -1, bestScore = -1;
        for (int c : p.hand) {
            if (!canPlay(pi, c)) continue;
            int v = val(c), s;
            if (col(c) == WILD) s = (p.hand.size() <= 2) ? 6 : 2;
            else if (v >= SKIP) s = 14 + (nextSize <= 2 ? 10 : 0);
            else s = 8 + v / 3;
            s += rnd.nextInt(3);
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return best;
    }

    public int botColor(int pi) {
        int[] n = new int[4];
        for (int c : players.get(pi).hand) if (col(c) < 4) n[col(c)]++;
        int b = rnd.nextInt(4), m = -1;
        for (int i = 0; i < 4; i++) if (n[i] > m) { m = n[i]; b = i; }
        return b;
    }

    // ---------------------------------------------------------------- JSON
    private static JSONArray arr(ArrayList<Integer> l) {
        JSONArray a = new JSONArray();
        for (int x : l) a.put(x);
        return a;
    }

    private static ArrayList<Integer> list(JSONArray a) {
        ArrayList<Integer> l = new ArrayList<Integer>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optInt(i));
        return l;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("seq", seq);
            o.put("status", "playing");
            o.put("turn", turn);
            o.put("dir", dir);
            o.put("color", color);
            o.put("over", over);
            o.put("wid", wid);
            o.put("winner", winner);
            o.put("drawnCard", drawnCard);
            o.put("juara", juara);
            o.put("ranks", arr(ranks));
            JSONArray ps = new JSONArray();
            for (P p : players) {
                JSONObject po = new JSONObject();
                po.put("id", p.id);
                po.put("name", p.name);
                po.put("bot", p.bot);
                po.put("seat", p.seat);
                po.put("hat", p.hat);
                po.put("face", p.face);
                po.put("col", p.col);
                po.put("acc", p.acc);
                po.put("body", p.body);
                po.put("skin", p.skin);
                po.put("hand", arr(p.hand));
                ps.put(po);
            }
            o.put("players", ps);
            o.put("draw", arr(draw));
            o.put("disc", arr(disc));
            JSONArray ea = new JSONArray();
            for (int[] e : evs) {
                JSONArray x = new JSONArray();
                for (int v : e) x.put(v);
                ea.put(x);
            }
            o.put("ev", ea);
        } catch (Exception ignored) { }
        return o;
    }

    /** Firebase membuang array kosong, jadi semua array dibaca dengan aman. */
    public void fromJson(JSONObject o) {
        seq = o.optInt("seq", 0);
        turn = o.optInt("turn", 0);
        dir = o.optInt("dir", 1);
        color = o.optInt("color", 0);
        over = o.optBoolean("over", false);
        wid = o.optString("wid", "");
        winner = o.optInt("winner", -1);
        drawnCard = o.optInt("drawnCard", -1);
        juara = o.optBoolean("juara", false);
        ranks = list(o.optJSONArray("ranks"));
        ArrayList<P> ps = new ArrayList<P>();
        JSONArray pa = o.optJSONArray("players");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.optJSONObject(i);
                if (po == null) continue;
                P p = new P();
                p.id = po.optString("id");
                p.name = po.optString("name");
                p.bot = po.optBoolean("bot", false);
                p.seat = po.optInt("seat", ps.size());
                p.hat = po.optInt("hat", 0);
                p.face = po.optInt("face", 0);
                p.col = po.optInt("col", 0);
                p.acc = po.optInt("acc", 0);
                p.body = po.optInt("body", 0);
                p.skin = po.optInt("skin", 0);
                p.hand = list(po.optJSONArray("hand"));
                ps.add(p);
            }
        }
        players = ps;
        draw = list(o.optJSONArray("draw"));
        disc = list(o.optJSONArray("disc"));
        evs = new ArrayList<int[]>();
        JSONArray ea = o.optJSONArray("ev");
        if (ea != null) {
            for (int i = 0; i < ea.length(); i++) {
                JSONArray x = ea.optJSONArray(i);
                if (x == null || x.length() < 4) continue;
                evs.add(new int[]{x.optInt(0), x.optInt(1), x.optInt(2), x.optInt(3)});
            }
        }
    }
}
