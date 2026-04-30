package com.codekeys.ime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * CommonWords — built-in English dictionary used by the suggestion strip when
 * no language-specific snippet matches the user's prefix.
 *
 * <p>The list is intentionally large enough (~600 entries) to give meaningful
 * autocomplete on day-to-day text, but small enough to stay in memory and avoid
 * needing on-device assets or network calls. Words are sorted by approximate
 * frequency so prefix matches surface the most common candidate first.
 *
 * <p>This is bundled — no external library, no internet — to keep the IME
 * usable offline (Sketchware-Pro friendly).
 */
final class CommonWords {

    private CommonWords() {}

    /**
     * Returns the immutable common-words dictionary. Sorted by descending
     * frequency: callers iterating it for prefix-match suggestions naturally
     * surface higher-frequency words first.
     */
    static List<String> all() {
        return WORDS;
    }

    private static final List<String> WORDS = Collections.unmodifiableList(Arrays.asList(
        // Top 200 most common English words (frequency ordered).
        "the","be","to","of","and","a","in","that","have","i",
        "it","for","not","on","with","he","as","you","do","at",
        "this","but","his","by","from","they","we","say","her","she",
        "or","an","will","my","one","all","would","there","their","what",
        "so","up","out","if","about","who","get","which","go","me",
        "when","make","can","like","time","no","just","him","know","take",
        "people","into","year","your","good","some","could","them","see","other",
        "than","then","now","look","only","come","its","over","think","also",
        "back","after","use","two","how","our","work","first","well","way",
        "even","new","want","because","any","these","give","day","most","us",
        "is","are","was","were","been","being","has","had","having","does",
        "did","done","doing","get","gets","getting","got","gotten","made","makes",
        "made","making","said","saying","going","went","gone","took","taken","taking",
        "saw","seen","seeing","came","coming","looked","looking","wanted","wanting","told",
        "telling","asked","asking","tried","trying","called","calling","thought","thinking","felt",
        "feeling","found","finding","let","letting","showed","showing","needed","needing","helped",
        // General everyday vocabulary
        "able","about","above","accept","across","act","action","actual","add","address",
        "again","against","age","agree","ahead","air","allow","almost","alone","along",
        "already","always","amount","another","answer","appear","area","around","ask","away",
        "baby","bad","ball","bank","base","beautiful","become","bed","before","begin",
        "behind","believe","below","best","better","between","big","bit","black","blue",
        "board","body","book","born","both","box","break","bring","brother","build",
        "business","buy","car","care","carry","case","catch","cause","center","certain",
        "change","check","child","children","choose","city","class","clean","clear","close",
        "cold","color","company","complete","computer","control","cool","copy","corner","cost",
        "country","course","create","cross","cry","current","cut","dance","dark","data",
        "date","dead","deal","death","decide","deep","describe","design","develop","die",
        "different","door","down","draw","dream","drink","drive","drop","during","each",
        "early","earth","east","easy","eat","education","effect","either","else","email",
        "empty","end","enough","enter","entire","environment","especially","every","everyone","everything",
        "example","exist","experience","explain","eye","face","fact","fail","fall","family",
        "far","fast","father","fear","feel","few","field","fight","figure","fill",
        "final","find","fine","fire","fish","five","floor","fly","follow","food",
        "foot","force","forget","form","forward","four","free","friend","front","full",
        "fun","future","game","general","girl","glass","government","great","green","ground",
        "group","grow","guess","guy","hair","half","hand","happen","happy","hard",
        "head","health","hear","heart","heavy","help","here","high","history","hit",
        "hold","home","hope","hot","hour","house","however","huge","human","hundred",
        "idea","important","include","increase","indeed","information","inside","instead","interest","issue",
        "job","join","keep","key","kid","kind","kitchen","language","large","last",
        "late","later","laugh","law","lay","lead","learn","least","leave","left",
        "leg","less","letter","level","life","light","line","list","listen","little",
        "live","local","long","lose","love","low","machine","main","major","manage",
        "many","market","matter","may","maybe","mean","meet","member","mention","might",
        "mind","minute","miss","model","money","month","more","morning","mother","mouth",
        "move","much","music","must","name","national","natural","near","necessary","never",
        "next","nice","night","none","north","nothing","notice","number","object","occur",
        "off","offer","office","often","oil","old","once","open","operation","opportunity",
        "order","others","outside","page","pair","paper","parent","park","part","particular",
        "pass","past","pay","peace","perhaps","person","picture","piece","place","plan",
        "play","point","police","policy","political","poor","popular","position","possible","power",
        "practice","prepare","present","president","pretty","price","probably","problem","process","produce",
        "product","program","project","property","provide","public","pull","purpose","push","put",
        "quality","question","quick","quickly","quiet","quite","race","raise","range","rate",
        "rather","reach","read","ready","real","reason","receive","recent","record","red",
        "region","relate","remain","remember","remove","report","require","research","rest","result",
        "return","rich","right","rise","road","rock","role","room","rule","run",
        "safe","same","save","school","science","sea","season","second","section","secure",
        "seek","seem","sell","send","sense","series","serious","serve","service","set",
        "seven","several","share","short","should","side","sign","similar","simple","since",
        "single","sister","sit","site","situation","size","skill","sky","sleep","small",
        "smile","social","society","soft","soldier","some","someone","something","sometimes","son",
        "song","soon","sort","sound","source","south","space","speak","special","specific",
        "spend","sport","spring","staff","stage","stand","standard","star","start","state",
        "station","stay","step","still","stop","store","story","street","strong","structure",
        "student","study","stuff","style","subject","success","such","suddenly","suffer","suggest",
        "summer","support","sure","surface","system","table","talk","task","teach","teacher",
        "team","technology","television","ten","term","test","than","thank","that","theory",
        "thing","third","those","though","thousand","threat","three","through","throughout","throw",
        "thus","today","together","tonight","too","top","total","tough","toward","town",
        "trade","traditional","training","travel","treat","tree","trial","trip","trouble","true",
        "truth","try","turn","type","under","understand","union","unit","until","upon",
        "value","various","very","view","visit","voice","wait","walk","wall","want",
        "war","watch","water","weapon","wear","week","weight","west","western","whatever",
        "wheel","whether","while","white","whole","whose","wide","wife","win","wind",
        "window","wish","within","without","woman","wonder","word","world","worry","worth",
        "write","wrong","yard","yeah","yes","yet","young","yourself"
    ));
}
