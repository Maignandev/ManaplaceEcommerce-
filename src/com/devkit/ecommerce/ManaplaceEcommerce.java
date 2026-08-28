package com.devkit.ecommerce;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.AsynchUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ManaplaceEcommerce
 * Moteur e-commerce indépendant de ManaplaceUtils.
 *
 * Le catalogue, panier, favoris, adresses et session sont conservés localement.
 * Les données serveur passent par l'API REST configurée.
 *
 * Contrat produit recommandé:
 * {
 *   "uid":"prod_123",
 *   "title":"Produit",
 *   "price":19.99,
 *   "image":"https://...",
 *   "stock":20,
 *   "category":"..."
 * }
 */
@DesignerComponent(
        version = 1,
        description = "ManaplaceEcommerce - moteur e-commerce pour Kodular/App Inventor compatible avec ManaplaceUtils.",
        category = ComponentCategory.EXTENSION,
        nonVisible = true
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class ManaplaceEcommerce extends AndroidNonvisibleComponent {

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String apiBaseUrl = "";
    private String authToken = "";
    private String userUid = "";

    private String productsEndpoint = "/products";
    private String cartEndpoint = "/cart";
    private String ordersEndpoint = "/orders";
    private String favoritesEndpoint = "/favorites";
    private String addressesEndpoint = "/addresses";
    private String couponEndpoint = "/coupons/validate";
    private String paymentEndpoint = "/payments/create";

    private final Map<String, JSONObject> products = new HashMap<>();
    private final Map<String, Integer> stock = new HashMap<>();
    private final Map<String, Integer> cart = new HashMap<>();
    private final Set<String> favorites = new HashSet<>();
    private final Map<String, JSONObject> addresses = new HashMap<>();

    private String couponCode = "";
    private double couponDiscount = 0;

    public ManaplaceEcommerce(ComponentContainer container) {
        super(container.$form());
        context = container.$context();
        activity = (Activity) container.$context();
        prefs = context.getSharedPreferences("manaplace_ecommerce", Context.MODE_PRIVATE);
        loadState();
    }

    /* ======================== JSON ======================== */

    private JSONObject object(String json) {
        try { return new JSONObject(json == null ? "{}" : json); }
        catch (Exception e) { return new JSONObject(); }
    }

    private JSONArray array(String json) {
        try { return new JSONArray(json == null ? "[]" : json); }
        catch (Exception e) { return new JSONArray(); }
    }

    private String uid(JSONObject o) {
        String v = o.optString("uid", "");
        return v.isEmpty() ? o.optString("id", "") : v;
    }

    private void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    /* ======================== PERSISTENCE ======================== */

    private synchronized void loadState() {
        authToken = prefs.getString("token", "");
        userUid = prefs.getString("user_uid", "");
        couponCode = prefs.getString("coupon", "");
        couponDiscount = Double.longBitsToDouble(
                prefs.getLong("discount", Double.doubleToLongBits(0)));

        JSONArray c = array(prefs.getString("cart", "[]"));
        for (int i=0; i<c.length(); i++) {
            JSONObject x = c.optJSONObject(i);
            if (x == null) continue;
            String id = x.optString("product_uid", "");
            int q = x.optInt("quantity", 0);
            if (!id.isEmpty() && q > 0) cart.put(id, q);
        }

        JSONArray f = array(prefs.getString("favorites", "[]"));
        for (int i=0; i<f.length(); i++) {
            String id = f.optString(i, "");
            if (!id.isEmpty()) favorites.add(id);
        }

        JSONArray a = array(prefs.getString("addresses", "[]"));
        for (int i=0; i<a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String id = uid(x);
            if (!id.isEmpty()) addresses.put(id, x);
        }
    }

    private synchronized void saveState() {
        JSONArray c = new JSONArray();
        for (Map.Entry<String,Integer> e : cart.entrySet()) {
            JSONObject x = new JSONObject();
            try {
                x.put("product_uid", e.getKey());
                x.put("quantity", e.getValue());
            } catch (Exception ignored) {}
            c.put(x);
        }

        JSONArray f = new JSONArray();
        for (String id : favorites) f.put(id);

        JSONArray a = new JSONArray();
        for (JSONObject x : addresses.values()) a.put(x);

        prefs.edit()
                .putString("token", authToken)
                .putString("user_uid", userUid)
                .putString("cart", c.toString())
                .putString("favorites", f.toString())
                .putString("addresses", a.toString())
                .putString("coupon", couponCode)
                .putLong("discount", Double.doubleToLongBits(couponDiscount))
                .apply();
    }

    /* ======================== CONFIG ======================== */

    @SimpleFunction(description="Définit l'URL de base HTTPS de l'API.")
    public void SetApiBaseUrl(String url) {
        apiBaseUrl = url == null ? "" : url.trim();
        while (apiBaseUrl.endsWith("/"))
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length()-1);
    }

    @SimpleFunction(description="Retourne l'URL de base de l'API.")
    public String GetApiBaseUrl() { return apiBaseUrl; }

    @SimpleFunction(description="Définit le token Bearer.")
    public void SetAuthToken(String token) {
        authToken = token == null ? "" : token;
        prefs.edit().putString("token", authToken).apply();
    }

    @SimpleFunction(description="Retourne le token Bearer.")
    public String GetAuthToken() { return authToken; }

    @SimpleFunction(description="Définit l'UID utilisateur.")
    public void SetUserUid(String id) {
        userUid = id == null ? "" : id;
        prefs.edit().putString("user_uid", userUid).apply();
        OnUserChanged(userUid);
    }

    @SimpleFunction(description="Retourne l'UID utilisateur.")
    public String GetUserUid() { return userUid; }

    @SimpleFunction(description="Indique si un utilisateur est connecté.")
    public boolean IsLoggedIn() { return !userUid.isEmpty() && !authToken.isEmpty(); }

    @SimpleFunction(description="Configure les endpoints principaux.")
    public void ConfigureEndpoints(
            String products, String cart, String orders,
            String favorites, String addresses, String coupon,
            String payment) {
        if (products != null && !products.isEmpty()) productsEndpoint = products;
        if (cart != null && !cart.isEmpty()) cartEndpoint = cart;
        if (orders != null && !orders.isEmpty()) ordersEndpoint = orders;
        if (favorites != null && !favorites.isEmpty()) favoritesEndpoint = favorites;
        if (addresses != null && !addresses.isEmpty()) addressesEndpoint = addresses;
        if (coupon != null && !coupon.isEmpty()) couponEndpoint = coupon;
        if (payment != null && !payment.isEmpty()) paymentEndpoint = payment;
    }

    private String endpoint(String p) {
        if (p == null) return apiBaseUrl;
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (apiBaseUrl.isEmpty()) return p;
        return apiBaseUrl + (p.startsWith("/") ? p : "/" + p);
    }

    /* ======================== COMMUNICATION MANAPLACEUTILS ======================== */

    @SimpleFunction(description="Reçoit un produit JSON provenant de ManaplaceUtils.")
    public void ReceiveProductFromUtils(String productJson) {
        JSONObject p = object(productJson);
        String id = uid(p);
        if (id.isEmpty()) {
            OnError("Produit sans UID.");
            return;
        }
        synchronized (products) {
            products.put(id, p);
            if (p.has("stock")) stock.put(id, Math.max(0, p.optInt("stock", 0)));
        }
        OnProductReceived(id, p.toString());
    }

    @SimpleFunction(description="Reçoit une liste JSON de produits provenant de ManaplaceUtils.")
    public void ReceiveProductsFromUtils(String productsJson) {
        JSONArray a = array(productsJson);
        int count = 0;
        synchronized (products) {
            for (int i=0; i<a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                String id = uid(p);
                if (id.isEmpty()) continue;
                products.put(id, p);
                if (p.has("stock")) stock.put(id, Math.max(0,p.optInt("stock",0)));
                count++;
            }
        }
        OnProductsReceived(count);
    }

    @SimpleFunction(description="Transmet le clic d'une carte produit de ManaplaceUtils au moteur e-commerce.")
    public void ProductCardClicked(String productUid, String productJson) {
        if (productUid == null || productUid.trim().isEmpty()) {
            OnError("ProductCardClicked: UID manquant.");
            return;
        }
        JSONObject p = object(productJson);
        try { if (!p.has("uid")) p.put("uid", productUid); }
        catch (Exception ignored) {}
        ReceiveProductFromUtils(p.toString());
        OnProductSelected(productUid, p.toString());
    }

    @SimpleFunction(description="Ajoute directement au panier le produit cliqué dans ManaplaceUtils.")
    public void AddClickedProductToCart(String productUid, String productJson) {
        ProductCardClicked(productUid, productJson);
        AddToCart(productUid, 1);
    }

    /* ======================== CATALOGUE ======================== */

    @SimpleFunction(description="Ajoute ou met à jour un produit.")
    public void UpsertProduct(String productJson) { ReceiveProductFromUtils(productJson); }

    @SimpleFunction(description="Retourne un produit par UID.")
    public String GetProduct(String productUid) {
        synchronized (products) {
            JSONObject p = products.get(productUid);
            return p == null ? "" : p.toString();
        }
    }

    @SimpleFunction(description="Retourne tous les produits.")
    public String GetAllProducts() {
        JSONArray a = new JSONArray();
        synchronized (products) { for (JSONObject p : products.values()) a.put(p); }
        return a.toString();
    }

    @SimpleFunction(description="Définit le stock connu d'un produit.")
    public void SetProductStock(String productUid, int quantity) {
        if (productUid == null || productUid.isEmpty()) return;
        int q = Math.max(0, quantity);
        synchronized (products) {
            stock.put(productUid, q);
            JSONObject p = products.get(productUid);
            if (p != null) try { p.put("stock", q); } catch (Exception ignored) {}
        }
        OnStockChanged(productUid, q);
    }

    @SimpleFunction(description="Retourne le stock connu, ou -1 si inconnu.")
    public int GetProductStock(String productUid) {
        synchronized (products) {
            Integer q = stock.get(productUid);
            if (q != null) return q;
            JSONObject p = products.get(productUid);
            return p == null ? -1 : p.optInt("stock", -1);
        }
    }

    /* ======================== PANIER ======================== */

    @SimpleFunction(description="Ajoute une quantité au panier.")
    public synchronized void AddToCart(String productUid, int quantity) {
        if (productUid == null || productUid.isEmpty()) {
            OnError("AddToCart: UID produit manquant."); return;
        }
        if (quantity <= 0) {
            OnError("AddToCart: quantité invalide."); return;
        }

        int old = cart.containsKey(productUid) ? cart.get(productUid) : 0;
        int wanted = old + quantity;
        int available = GetProductStock(productUid);

        if (available >= 0 && wanted > available) {
            OnError("Stock insuffisant pour " + productUid + ".");
            return;
        }

        cart.put(productUid, wanted);
        saveState();
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Définit la quantité d'un produit.")
    public synchronized void SetCartQuantity(String productUid, int quantity) {
        if (productUid == null || productUid.isEmpty()) return;
        if (quantity <= 0) cart.remove(productUid);
        else {
            int available = GetProductStock(productUid);
            if (available >= 0 && quantity > available) {
                OnError("Stock insuffisant."); return;
            }
            cart.put(productUid, quantity);
        }
        saveState();
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Diminue la quantité d'une unité.")
    public synchronized void DecreaseCartQuantity(String productUid) {
        Integer q = cart.get(productUid);
        if (q == null) return;
        if (q <= 1) cart.remove(productUid);
        else cart.put(productUid, q-1);
        saveState();
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Supprime un produit du panier.")
    public synchronized void RemoveFromCart(String productUid) {
        cart.remove(productUid);
        saveState();
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Vide le panier et le coupon.")
    public synchronized void ClearCart() {
        cart.clear();
        couponCode = "";
        couponDiscount = 0;
        saveState();
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Retourne la quantité d'un produit.")
    public synchronized int GetCartQuantity(String productUid) {
        Integer q = cart.get(productUid); return q == null ? 0 : q;
    }

    @SimpleFunction(description="Retourne le nombre total d'articles.")
    public synchronized int GetCartItemCount() {
        int n=0; for (Integer q:cart.values()) n+=q; return n;
    }

    @SimpleFunction(description="Retourne le nombre de lignes du panier.")
    public synchronized int GetCartLineCount() { return cart.size(); }

    @SimpleFunction(description="Retourne le panier avec UID, titre, image, prix, quantité et sous-total.")
    public synchronized String GetCartJson() {
        JSONArray a = new JSONArray();
        synchronized (products) {
            for (Map.Entry<String,Integer> e : cart.entrySet()) {
                JSONObject x = new JSONObject();
                JSONObject p = products.get(e.getKey());
                try {
                    x.put("product_uid", e.getKey());
                    x.put("quantity", e.getValue());
                    if (p != null) {
                        x.put("title", p.optString("title",""));
                        x.put("image", p.optString("image",""));
                        x.put("price", p.optDouble("price",0));
                        x.put("currency", p.optString("currency",""));
                        x.put("subtotal", p.optDouble("price",0)*e.getValue());
                        Iterator<String> k=p.keys();
                        while(k.hasNext()) {
                            String key=k.next();
                            if(!x.has(key)) x.put(key,p.opt(key));
                        }
                    }
                } catch(Exception ignored){}
                a.put(x);
            }
        }
        return a.toString();
    }

    @SimpleFunction(description="Retourne le sous-total du panier.")
    public synchronized double GetCartSubtotal() {
        double total=0;
        synchronized (products) {
            for(Map.Entry<String,Integer> e:cart.entrySet()) {
                JSONObject p=products.get(e.getKey());
                if(p!=null) total+=p.optDouble("price",0)*e.getValue();
            }
        }
        return total;
    }

    @SimpleFunction(description="Retourne le total après remise, hors livraison/taxes.")
    public synchronized double GetCartTotal() {
        return Math.max(0,GetCartSubtotal()-couponDiscount);
    }

    @SimpleFunction(description="Construit le JSON de checkout.")
    public synchronized String BuildCheckoutJson(
            String addressUid, String shippingMethod, String paymentMethod) {
        JSONObject x=new JSONObject();
        try {
            x.put("user_uid",userUid);
            x.put("address_uid",addressUid==null?"":addressUid);
            x.put("shipping_method",shippingMethod==null?"":shippingMethod);
            x.put("payment_method",paymentMethod==null?"":paymentMethod);
            x.put("coupon_code",couponCode);
            x.put("discount",couponDiscount);
            x.put("subtotal",GetCartSubtotal());
            x.put("total",GetCartTotal());
            x.put("items",array(GetCartJson()));
        } catch(Exception ignored){}
        return x.toString();
    }

    /* ======================== FAVORIS ======================== */

    @SimpleFunction(description="Ajoute un produit aux favoris.")
    public synchronized void AddFavorite(String productUid) {
        if(productUid==null||productUid.isEmpty())return;
        favorites.add(productUid); saveState();
        OnFavoriteChanged(productUid,true);
    }

    @SimpleFunction(description="Retire un produit des favoris.")
    public synchronized void RemoveFavorite(String productUid) {
        favorites.remove(productUid); saveState();
        OnFavoriteChanged(productUid,false);
    }

    @SimpleFunction(description="Bascule un favori.")
    public synchronized void ToggleFavorite(String productUid) {
        if(favorites.contains(productUid)) RemoveFavorite(productUid);
        else AddFavorite(productUid);
    }

    @SimpleFunction(description="Indique si un produit est favori.")
    public synchronized boolean IsFavorite(String productUid) {
        return favorites.contains(productUid);
    }

    @SimpleFunction(description="Retourne les UID favoris.")
    public synchronized String GetFavoritesJson() {
        JSONArray a=new JSONArray(); for(String id:favorites)a.put(id); return a.toString();
    }

    /* ======================== RECHERCHE ======================== */

    @SimpleFunction(description="Recherche et filtre localement les produits.")
    public String SearchProducts(String query,double minPrice,double maxPrice,
                                 String category,String sortBy,boolean descending) {
        String q=query==null?"":query.trim().toLowerCase(Locale.US);
        String c=category==null?"":category.trim().toLowerCase(Locale.US);
        List<JSONObject> r=new ArrayList<>();

        synchronized(products) {
            for(JSONObject p:products.values()) {
                double price=p.optDouble("price",0);
                if(minPrice>=0&&price<minPrice)continue;
                if(maxPrice>=0&&price>maxPrice)continue;

                String text=(p.optString("uid","")+" "+
                        p.optString("title","")+" "+
                        p.optString("description","")+" "+
                        p.optString("category","")).toLowerCase(Locale.US);

                if(!q.isEmpty()&&!text.contains(q))continue;
                if(!c.isEmpty()&&!p.optString("category","")
                        .toLowerCase(Locale.US).contains(c))continue;
                r.add(p);
            }
        }

        final String s=sortBy==null?"":sortBy.toLowerCase(Locale.US);
        if("price".equals(s)) Collections.sort(r,new Comparator<JSONObject>(){
            public int compare(JSONObject a,JSONObject b){
                return Double.compare(a.optDouble("price",0),b.optDouble("price",0));
            }});
        else if("title".equals(s)) Collections.sort(r,new Comparator<JSONObject>(){
            public int compare(JSONObject a,JSONObject b){
                return a.optString("title","").compareToIgnoreCase(b.optString("title",""));
            }});
        if(descending)Collections.reverse(r);

        JSONArray out=new JSONArray();for(JSONObject p:r)out.put(p);return out.toString();
    }

    /* ======================== COUPON ======================== */

    @SimpleFunction(description="Applique une remise fixe locale.")
    public synchronized void ApplyFixedDiscount(String code,double discount) {
        if(discount<0){OnError("Remise invalide.");return;}
        couponCode=code==null?"":code;
        couponDiscount=Math.min(discount,GetCartSubtotal());
        saveState();
        OnCouponApplied(couponCode,couponDiscount);
        OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Supprime le coupon.")
    public synchronized void ClearCoupon() {
        couponCode="";couponDiscount=0;saveState();
        OnCouponApplied("",0);OnCartChanged(GetCartJson());
    }

    @SimpleFunction(description="Retourne le coupon appliqué.")
    public String GetAppliedCouponCode(){return couponCode;}

    @SimpleFunction(description="Retourne la remise appliquée.")
    public double GetCouponDiscount(){return couponDiscount;}

    /* ======================== ADRESSES ======================== */

    @SimpleFunction(description="Sauvegarde une adresse locale.")
    public synchronized void SaveAddress(String addressJson) {
        JSONObject a=object(addressJson);String id=uid(a);
        if(id.isEmpty()){id="addr_"+UUID.randomUUID();try{a.put("uid",id);}catch(Exception ignored){}}
        addresses.put(id,a);saveState();OnAddressSaved(id,a.toString());
    }

    @SimpleFunction(description="Supprime une adresse.")
    public synchronized void DeleteAddress(String addressUid) {
        addresses.remove(addressUid);saveState();OnAddressDeleted(addressUid);
    }

    @SimpleFunction(description="Retourne une adresse.")
    public synchronized String GetAddress(String addressUid) {
        JSONObject a=addresses.get(addressUid);return a==null?"":a.toString();
    }

    @SimpleFunction(description="Retourne toutes les adresses.")
    public synchronized String GetAddressesJson() {
        JSONArray a=new JSONArray();for(JSONObject x:addresses.values())a.put(x);return a.toString();
    }

    /* ======================== AUTH ======================== */

    @SimpleFunction(description="Envoie une connexion au backend.")
    public void Login(String email,String password) {
        JSONObject x=new JSONObject();
        try{x.put("email",email==null?"":email);x.put("password",password==null?"":password);}
        catch(Exception ignored){}
        ApiRequest("/auth/login","POST","",x.toString());
    }

    @SimpleFunction(description="Envoie une inscription au backend.")
    public void Register(String email,String password,String displayName) {
        JSONObject x=new JSONObject();
        try{x.put("email",email==null?"":email);x.put("password",password==null?"":password);
            x.put("display_name",displayName==null?"":displayName);}catch(Exception ignored){}
        ApiRequest("/auth/register","POST","",x.toString());
    }

    @SimpleFunction(description="Déconnecte la session locale.")
    public synchronized void Logout() {
        userUid="";authToken="";favorites.clear();addresses.clear();
        couponCode="";couponDiscount=0;saveState();OnLoggedOut();
    }

    /* ======================== SERVER ======================== */

    private Map<String,String> headers(String json) {
        Map<String,String> h=new HashMap<>();
        h.put("Accept","application/json");
        h.put("Content-Type","application/json; charset=utf-8");
        if(!authToken.isEmpty())h.put("Authorization","Bearer "+authToken);
        if(!userUid.isEmpty())h.put("X-User-Uid",userUid);

        if(json!=null&&!json.trim().isEmpty()) {
            JSONObject o=object(json);Iterator<String> k=o.keys();
            while(k.hasNext()){String key=k.next();h.put(key,o.optString(key,""));}
        }
        return h;
    }

    @SimpleFunction(description="Requête REST GET/POST/PUT/PATCH/DELETE.")
    public void ApiRequest(final String path,final String method,
                           final String headersJson,final String bodyJson) {
        AsynchUtil.runAsynchronously(new Runnable(){public void run(){
            HttpURLConnection c=null;
            try {
                URL u=new URL(endpoint(path));c=(HttpURLConnection)u.openConnection();
                c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setUseCaches(false);
                String m=method==null?"GET":method.toUpperCase(Locale.US);
                if(!m.equals("GET")&&!m.equals("POST")&&!m.equals("PUT")&&!m.equals("PATCH")&&!m.equals("DELETE"))
                    m="GET";
                c.setRequestMethod(m);
                for(Map.Entry<String,String> e:headers(headersJson).entrySet())
                    c.setRequestProperty(e.getKey(),e.getValue());

                if(!m.equals("GET")) {
                    c.setDoOutput(true);
                    OutputStream o=c.getOutputStream();
                    o.write((bodyJson==null?"":bodyJson).getBytes("UTF-8"));o.flush();o.close();
                }

                final int code=c.getResponseCode();
                InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();
                final String response=read(in);

                ui(new Runnable(){public void run(){OnApiResponse(code,response);}});
            } catch(final Exception e) {
                ui(new Runnable(){public void run(){
                    OnApiError(0,e.getMessage()==null?"Erreur réseau.":e.getMessage());
                }});
            } finally {if(c!=null)c.disconnect();}
        }});
    }

    private String read(InputStream in)throws Exception{
        if(in==null)return "";
        BufferedReader r=new BufferedReader(new InputStreamReader(in,"UTF-8"));
        StringBuilder s=new StringBuilder();String line;
        while((line=r.readLine())!=null)s.append(line);
        r.close();return s.toString();
    }

    private String enc(String s){
        try{return URLEncoder.encode(s==null?"":s,"UTF-8");}
        catch(Exception e){return s==null?"":s;}
    }

    /* ======================== API METIERS ======================== */

    @SimpleFunction(description="Charge le catalogue depuis le serveur.")
    public void LoadProductsFromServer(){ApiRequest(productsEndpoint,"GET","","");}

    @SimpleFunction(description="Synchronise le panier local.")
    public void SyncCartToServer(){
        JSONObject x=new JSONObject();
        try{x.put("user_uid",userUid);x.put("items",array(GetCartJson()));}
        catch(Exception ignored){}
        ApiRequest(cartEndpoint,"POST","",x.toString());
    }

    @SimpleFunction(description="Charge le panier du serveur.")
    public void LoadCartFromServer(){
        String p=cartEndpoint+(cartEndpoint.contains("?")?"&":"?")+"user_uid="+enc(userUid);
        ApiRequest(p,"GET","","");
    }

    @SimpleFunction(description="Synchronise les favoris.")
    public void SyncFavoritesToServer(){
        JSONObject x=new JSONObject();
        try{x.put("user_uid",userUid);x.put("product_uids",array(GetFavoritesJson()));}
        catch(Exception ignored){}
        ApiRequest(favoritesEndpoint,"POST","",x.toString());
    }

    @SimpleFunction(description="Valide un coupon sur le serveur.")
    public void ValidateCoupon(String code){
        JSONObject x=new JSONObject();
        try{x.put("user_uid",userUid);x.put("coupon_code",code==null?"":code);
            x.put("subtotal",GetCartSubtotal());}catch(Exception ignored){}
        ApiRequest(couponEndpoint,"POST","",x.toString());
    }

    @SimpleFunction(description="Crée une commande côté serveur.")
    public void CreateOrder(String addressUid,String shippingMethod,String paymentMethod){
        if(cart.isEmpty()){OnError("Panier vide.");return;}
        ApiRequest(ordersEndpoint,"POST","",
                BuildCheckoutJson(addressUid,shippingMethod,paymentMethod));
    }

    @SimpleFunction(description="Charge les commandes de l'utilisateur.")
    public void LoadOrders(){
        String p=ordersEndpoint+(ordersEndpoint.contains("?")?"&":"?")+"user_uid="+enc(userUid);
        ApiRequest(p,"GET","","");
    }

    @SimpleFunction(description="Récupère une commande par UID.")
    public void GetOrder(String orderUid){
        ApiRequest(ordersEndpoint+"/"+enc(orderUid),"GET","","");
    }

    @SimpleFunction(description="Crée une intention de paiement côté serveur. Le paiement réel doit être validé par le backend.")
    public void CreatePaymentIntent(String orderUid,String provider,double amount,String currency){
        JSONObject x=new JSONObject();
        try{x.put("user_uid",userUid);x.put("order_uid",orderUid);
            x.put("provider",provider);x.put("amount",amount);x.put("currency",currency);}
        catch(Exception ignored){}
        ApiRequest(paymentEndpoint,"POST","",x.toString());
    }

    @SimpleFunction(description="Envoie une adresse au serveur.")
    public void UploadAddress(String addressJson){
        JSONObject x=object(addressJson);try{x.put("user_uid",userUid);}catch(Exception ignored){}
        ApiRequest(addressesEndpoint,"POST","",x.toString());
    }

    @SimpleFunction(description="Charge les adresses du serveur.")
    public void LoadAddressesFromServer(){
        String p=addressesEndpoint+(addressesEndpoint.contains("?")?"&":"?")+"user_uid="+enc(userUid);
        ApiRequest(p,"GET","","");
    }

    /* ======================== EVENEMENTS ======================== */

    @SimpleEvent public void OnProductReceived(String productUid,String productJson){
        EventDispatcher.dispatchEvent(this,"OnProductReceived",productUid,productJson);
    }

    @SimpleEvent public void OnProductsReceived(int count){
        EventDispatcher.dispatchEvent(this,"OnProductsReceived",count);
    }

    @SimpleEvent public void OnProductSelected(String productUid,String productJson){
        EventDispatcher.dispatchEvent(this,"OnProductSelected",productUid,productJson);
    }

    @SimpleEvent public void OnCartChanged(String cartJson){
        EventDispatcher.dispatchEvent(this,"OnCartChanged",cartJson);
    }

    @SimpleEvent public void OnFavoriteChanged(String productUid,boolean favorite){
        EventDispatcher.dispatchEvent(this,"OnFavoriteChanged",productUid,favorite);
    }

    @SimpleEvent public void OnStockChanged(String productUid,int quantity){
        EventDispatcher.dispatchEvent(this,"OnStockChanged",productUid,quantity);
    }

    @SimpleEvent public void OnCouponApplied(String code,double discount){
        EventDispatcher.dispatchEvent(this,"OnCouponApplied",code,discount);
    }

    @SimpleEvent public void OnAddressSaved(String addressUid,String addressJson){
        EventDispatcher.dispatchEvent(this,"OnAddressSaved",addressUid,addressJson);
    }

    @SimpleEvent public void OnAddressDeleted(String addressUid){
        EventDispatcher.dispatchEvent(this,"OnAddressDeleted",addressUid);
    }

    @SimpleEvent public void OnUserChanged(String userUid){
        EventDispatcher.dispatchEvent(this,"OnUserChanged",userUid);
    }

    @SimpleEvent public void OnApiResponse(int responseCode,String responseJson){
        EventDispatcher.dispatchEvent(this,"OnApiResponse",responseCode,responseJson);
    }

    @SimpleEvent public void OnApiError(int responseCode,String message){
        EventDispatcher.dispatchEvent(this,"OnApiError",responseCode,message);
    }

    @SimpleEvent public void OnLoggedOut(){
        EventDispatcher.dispatchEvent(this,"OnLoggedOut");
    }

    @SimpleEvent public void OnError(String message){
        EventDispatcher.dispatchEvent(this,"OnError",message);
    }
}
