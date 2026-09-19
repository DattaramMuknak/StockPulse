package com.stockpulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootApplication
@EnableAsync
public class StockPulseApplication { public static void main(String[] args) { SpringApplication.run(StockPulseApplication.class, args); } }

enum Category { ELECTRONICS, APPAREL, HOME }
enum ProductStatus { ACTIVE, PRICE_REVIEW_PENDING, OUT_OF_STOCK }
enum SuggestionStatus { PENDING, ACCEPTED, REJECTED }
enum TriggerReason { INITIAL, INVENTORY_LOW, DEMAND_SPIKE, MANUAL }
enum Direction { INCREASE, DECREASE, HOLD }

@Entity @Table(name="products") class Product {
  @Id public String id; @Column(unique=true, nullable=false) public String sku; public String name; @Enumerated(EnumType.STRING) public Category category;
  @Column(precision=12, scale=2) public BigDecimal currentPrice; public int stockLevel; public int reorderThreshold; public int demandVelocity;
  @Enumerated(EnumType.STRING) public ProductStatus status = ProductStatus.ACTIVE;
  @Column(precision=12, scale=2) public BigDecimal costPrice; @Column(precision=12, scale=2) public BigDecimal marginFloor;
  protected Product() {}
  Product(String id,String sku,String name,Category category,BigDecimal price,int stock,int threshold,int velocity) { this.id=id;this.sku=sku;this.name=name;this.category=category;this.currentPrice=price;this.stockLevel=stock;this.reorderThreshold=threshold;this.demandVelocity=velocity; syncStatus(); }
  void syncStatus(){ status=stockLevel==0?ProductStatus.OUT_OF_STOCK:ProductStatus.ACTIVE; }
}
@Entity class PricingSuggestion {
 @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id; @ManyToOne(optional=false) public Product product; @Column(precision=12,scale=2) public BigDecimal currentPrice,recommendedPrice;
 @Enumerated(EnumType.STRING) public Direction direction; public double confidence; @Column(length=2000) public String reasoning; public String source; @Enumerated(EnumType.STRING) public SuggestionStatus status=SuggestionStatus.PENDING; @Enumerated(EnumType.STRING) public TriggerReason triggerReason; public Instant createdAt=Instant.now(); public Instant decidedAt; protected PricingSuggestion(){}
}
@Entity class ReorderSuggestion {
 @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id; @ManyToOne(optional=false) public Product product; public int currentStock,recommendedQuantity,suggestedLeadTimeDays; public double confidence; @Column(length=2000) public String reasoning; public String source; @Enumerated(EnumType.STRING) public SuggestionStatus status=SuggestionStatus.PENDING; @Enumerated(EnumType.STRING) public TriggerReason triggerReason; public Instant createdAt=Instant.now(); public Instant decidedAt; protected ReorderSuggestion(){}
}
interface ProductRepo extends JpaRepository<Product,String> { List<Product> findByCategory(Category c); List<Product> findByStatus(ProductStatus s); }
interface PricingRepo extends JpaRepository<PricingSuggestion,UUID> { boolean existsByProductIdAndTriggerReasonAndStatus(String p,TriggerReason t,SuggestionStatus s); List<PricingSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus s); }
interface ReorderRepo extends JpaRepository<ReorderSuggestion,UUID> { boolean existsByProductIdAndTriggerReasonAndStatus(String p,TriggerReason t,SuggestionStatus s); List<ReorderSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus s); }

record InventorySignal(String productId, TriggerReason reason) {}
record Advice(BigDecimal price, Direction direction, double priceConfidence, String priceReasoning, int quantity, double reorderConfidence, String reorderReasoning, String source) {}
interface CommerceAdvisor { Advice advise(Product p, TriggerReason trigger); }

@Component("rule") class RuleAdvisor implements CommerceAdvisor {
  private final ProductRepo products; RuleAdvisor(ProductRepo products){this.products=products;}
  double peerAverage(Product p){return products.findByCategory(p.category).stream().filter(x->!x.id.equals(p.id)).mapToInt(x->x.demandVelocity).average().orElse(1);}
  public Advice advise(Product p, TriggerReason trigger) {
    double categoryAvg=peerAverage(p);
    Direction d=Direction.HOLD; BigDecimal price=p.currentPrice; String why="Demand and inventory are within normal operating ranges.";
    if(p.stockLevel<p.reorderThreshold){ d=Direction.INCREASE; price=p.currentPrice.multiply(new BigDecimal("1.10")); why="Stock is below the reorder threshold; a modest increase protects remaining inventory while replenishment is considered."; }
    else if(p.demandVelocity>categoryAvg*2){ d=Direction.INCREASE; price=p.currentPrice.multiply(new BigDecimal("1.05")); why="Demand is more than twice the category average; a modest increase captures the demand spike."; }
    int quantity=Math.max(1,p.reorderThreshold*3-p.stockLevel);
    return new Advice(price.setScale(2,RoundingMode.HALF_UP),d,.72,why,quantity,.70,"Reorder to cover three threshold cycles after current inventory.","RULE ENGINE");
  }
}

@Component("ai") class AiAdvisor implements CommerceAdvisor {
  private final RuleAdvisor fallback; private final ObjectMapper json;
  @Value("${app.llm.base-url:}") String url; @Value("${app.llm.api-key:}") String key; @Value("${app.llm.cookie:}") String cookie; @Value("${app.llm.product:PC1}") String product; @Value("${app.llm.model:qwen-cursor}") String model;
  AiAdvisor(RuleAdvisor fallback,ObjectMapper json){this.fallback=fallback;this.json=json;}
  public Advice advise(Product p, TriggerReason trigger) {
    if(url==null||url.isBlank()||key==null||key.isBlank()) return fallback.advise(p,trigger);
    try {
      double categoryAverage=fallback.peerAverage(p);
      String prompt="You are a commerce advisor. Return ONLY JSON with recommendedPrice, direction, priceConfidence, priceReasoning, recommendedQuantity, reorderConfidence, reorderReasoning. Product="+p.name+", category="+p.category+", price="+p.currentPrice+", stock="+p.stockLevel+", reorderThreshold="+p.reorderThreshold+", ordersLast24h="+p.demandVelocity+", categoryPeerAverage="+categoryAverage+", trigger="+trigger+". For INVENTORY_LOW, balance protecting remaining stock against clearing slow stock. For DEMAND_SPIKE, assess a modest increase without overreacting. Explain the tradeoff plainly. Price must be within 50% to 150% of current price; quantity positive.";
      Map<String,Object> body=Map.of("model",model,"messages",List.of(Map.of("role","user","content",prompt)),"temperature",0.2);
      SimpleClientHttpRequestFactory requestFactory=new SimpleClientHttpRequestFactory(); requestFactory.setConnectTimeout(5_000); requestFactory.setReadTimeout(12_000);
      String raw=CompletableFuture.supplyAsync(()->RestClient.builder().requestFactory(requestFactory).build().post().uri(url).header(HttpHeaders.AUTHORIZATION,"Bearer "+key).header("product",product).headers(headers->{if(cookie!=null&&!cookie.isBlank())headers.add(HttpHeaders.COOKIE,cookie);}).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class)).get(12,TimeUnit.SECONDS);
      JsonNode n=json.readTree(raw).path("choices").path(0).path("message").path("content"); String content=n.asText().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","").trim(); JsonNode a=json.readTree(content);
      BigDecimal price=a.path("recommendedPrice").decimalValue(); int q=a.path("recommendedQuantity").asInt(); double pc=a.path("priceConfidence").asDouble(), rc=a.path("reorderConfidence").asDouble();
      if(price.compareTo(p.currentPrice.multiply(new BigDecimal(".5")))<0||price.compareTo(p.currentPrice.multiply(new BigDecimal("1.5")))>0||q<1||pc<0||pc>1||rc<0||rc>1) throw new IllegalArgumentException("Unsafe model output");
      return new Advice(price.setScale(2,RoundingMode.HALF_UP),Direction.valueOf(a.path("direction").asText("HOLD")),pc,a.path("priceReasoning").asText(),q,rc,a.path("reorderReasoning").asText(),"AI · "+model);
    } catch(Exception ignored) { Advice rule=fallback.advise(p,trigger); return new Advice(rule.price(),rule.direction(),rule.priceConfidence(),rule.priceReasoning(),rule.quantity(),rule.reorderConfidence(),rule.reorderReasoning(),"RULE FALLBACK"); }
  }
}
@Component class AdvisorRegistry { private final Map<String,CommerceAdvisor> advisors; @Value("${app.strategy:rule}") String active; AdvisorRegistry(Map<String,CommerceAdvisor> advisors){this.advisors=advisors;} CommerceAdvisor current(){return advisors.getOrDefault(active.toLowerCase(),advisors.get("rule"));} String activeName(){return advisors.containsKey(active.toLowerCase())?active.toUpperCase():"RULE";} }

@Service class InventoryService {
 private final ProductRepo products; private final PricingRepo pricing; private final ReorderRepo reorders; private final ApplicationEventPublisher events; private final AdvisorRegistry registry; @Value("${app.demand-spike-multiplier:3}") double spike;
 InventoryService(ProductRepo p,PricingRepo pr,ReorderRepo rr,ApplicationEventPublisher e,AdvisorRegistry r){products=p;pricing=pr;reorders=rr;events=e;registry=r;}
 @Transactional Product stock(String id,int level){ Product p=get(id);p.stockLevel=level;p.syncStatus();products.save(p);signalIfNeeded(p);return p; }
 @Transactional Product order(String id,int qty){ Product p=get(id);p.stockLevel=Math.max(0,p.stockLevel-qty);p.demandVelocity+=qty;p.syncStatus();products.save(p);signalIfNeeded(p);return p; }
 @Transactional void manual(String id,TriggerReason t){ events.publishEvent(new InventorySignal(id,t)); }
 @Transactional void generate(String id,TriggerReason trigger){ Product p=products.findById(id).orElse(null); if(p==null)return; boolean hasPrice=pricing.existsByProductIdAndTriggerReasonAndStatus(id,trigger,SuggestionStatus.PENDING); boolean hasReorder=reorders.existsByProductIdAndTriggerReasonAndStatus(id,trigger,SuggestionStatus.PENDING); if(hasPrice&&hasReorder)return; Advice a=registry.current().advise(p,trigger); if(!hasPrice){PricingSuggestion ps=new PricingSuggestion();ps.product=p;ps.currentPrice=p.currentPrice;ps.recommendedPrice=a.price();ps.direction=a.direction();ps.confidence=a.priceConfidence();ps.reasoning=a.priceReasoning();ps.source=a.source();ps.triggerReason=trigger;pricing.save(ps);} if(!hasReorder){ReorderSuggestion rs=new ReorderSuggestion();rs.product=p;rs.currentStock=p.stockLevel;rs.recommendedQuantity=a.quantity();rs.suggestedLeadTimeDays=7;rs.confidence=a.reorderConfidence();rs.reasoning=a.reorderReasoning();rs.source=a.source();rs.triggerReason=trigger;reorders.save(rs);} p.status=ProductStatus.PRICE_REVIEW_PENDING; }
 @Transactional void decidePrice(UUID id,SuggestionStatus decision){ PricingSuggestion s=pricing.findById(id).orElseThrow();if(s.status!=SuggestionStatus.PENDING)throw new IllegalStateException("Suggestion already decided");s.status=decision;s.decidedAt=Instant.now();if(decision==SuggestionStatus.ACCEPTED)s.product.currentPrice=s.recommendedPrice;s.product.syncStatus(); }
 @Transactional void decideReorder(UUID id,SuggestionStatus decision){ ReorderSuggestion s=reorders.findById(id).orElseThrow();if(s.status!=SuggestionStatus.PENDING)throw new IllegalStateException("Suggestion already decided");s.status=decision;s.decidedAt=Instant.now();if(decision==SuggestionStatus.ACCEPTED){s.product.stockLevel+=s.recommendedQuantity;s.product.syncStatus();} }
 Product get(String id){return products.findById(id).orElseThrow(()->new NoSuchElementException("Product not found"));}
 private void signalIfNeeded(Product p){ double avg=products.findByCategory(p.category).stream().filter(x->!x.id.equals(p.id)).mapToInt(x->x.demandVelocity).average().orElse(1); if(p.stockLevel<p.reorderThreshold)events.publishEvent(new InventorySignal(p.id,TriggerReason.INVENTORY_LOW)); if(p.demandVelocity>avg*spike)events.publishEvent(new InventorySignal(p.id,TriggerReason.DEMAND_SPIKE)); }
}
@Component class RecommendationListener { private final InventoryService service; RecommendationListener(InventoryService s){service=s;} @Async @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT) public void on(InventorySignal s){service.generate(s.productId(),s.reason());} }

record CreateProduct(@NotBlank String sku,@NotBlank String name,@NotNull Category category,@NotNull @DecimalMin("0.01") BigDecimal currentPrice,@Min(0) int stockLevel,@Min(1) int reorderThreshold,@Min(0) int demandVelocity){}
record StockChange(@Min(0) int stockLevel){} record Order(@Min(1) int quantity){} record Decision(@NotNull SuggestionStatus status){}
@RestController @RequestMapping class Api {
 private final ProductRepo products; private final PricingRepo pricing; private final ReorderRepo reorders; private final InventoryService inventory; private final AdvisorRegistry advisors; private final AiAdvisor ai;
 Api(ProductRepo p,PricingRepo ps,ReorderRepo rs,InventoryService i,AdvisorRegistry a,AiAdvisor ai){products=p;pricing=ps;reorders=rs;inventory=i;advisors=a;this.ai=ai;}
 @GetMapping("/system/status") Map<String,Object> systemStatus(){return Map.of("activeStrategy",advisors.activeName(),"aiGatewayConfigured",ai.url!=null&&!ai.url.isBlank()&&ai.key!=null&&!ai.key.isBlank(),"model",ai.model,"humanApprovalRequired",true);}
 @GetMapping("/products") List<Product> list(@RequestParam(required=false) Category category,@RequestParam(required=false) ProductStatus status){if(category!=null)return products.findByCategory(category);if(status!=null)return products.findByStatus(status);return products.findAll();}
 @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) Product create(@Valid @RequestBody CreateProduct r){return products.save(new Product(UUID.randomUUID().toString(),r.sku(),r.name(),r.category(),r.currentPrice(),r.stockLevel(),r.reorderThreshold(),r.demandVelocity()));}
 @PatchMapping("/products/{id}/stock") Product stock(@PathVariable String id,@Valid @RequestBody StockChange r){return inventory.stock(id,r.stockLevel());}
 @PostMapping("/products/{id}/orders") Product order(@PathVariable String id,@Valid @RequestBody Order r){return inventory.order(id,r.quantity());}
 @PostMapping("/products/{id}/suggest-pricing") @ResponseStatus(HttpStatus.ACCEPTED) void price(@PathVariable String id){inventory.manual(id,TriggerReason.MANUAL);}
 @PostMapping("/products/{id}/suggest-reorder") @ResponseStatus(HttpStatus.ACCEPTED) void reorder(@PathVariable String id){inventory.manual(id,TriggerReason.MANUAL);}
 @GetMapping("/pricing-suggestions") List<PricingSuggestion> prices(@RequestParam(defaultValue="PENDING") SuggestionStatus status){return pricing.findByStatusOrderByCreatedAtDesc(status);}
 @GetMapping("/reorder-suggestions") List<ReorderSuggestion> reorders(@RequestParam(defaultValue="PENDING") SuggestionStatus status){return reorders.findByStatusOrderByCreatedAtDesc(status);}
 @PatchMapping("/pricing-suggestions/{id}") void decidePrice(@PathVariable UUID id,@Valid @RequestBody Decision d){inventory.decidePrice(id,d.status());}
 @PatchMapping("/reorder-suggestions/{id}") void decideReorder(@PathVariable UUID id,@Valid @RequestBody Decision d){inventory.decideReorder(id,d.status());}
}
@RestControllerAdvice class ApiErrors {
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<Map<String,String>> missing(NoSuchElementException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<Map<String,String>> conflict(IllegalStateException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",e.getMessage()));}
}
@Configuration class Cors { @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer corsConfigurer(){return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer(){public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry r){r.addMapping("/**").allowedOrigins("http://localhost:5173").allowedMethods("*");}};} }
@Configuration class Seed { @Bean CommandLineRunner seedData(ProductRepo r){return x->{ if(r.count()>0)return; r.saveAll(List.of(new Product("PRD-001","SKU-ELEC-001","Wireless Earbuds Pro",Category.ELECTRONICS,new BigDecimal("79.99"),45,20,3),new Product("PRD-002","SKU-ELEC-002","USB-C Hub 7-Port",Category.ELECTRONICS,new BigDecimal("34.99"),120,30,1),new Product("PRD-003","SKU-APP-001","Organic Cotton T-Shirt",Category.APPAREL,new BigDecimal("24.99"),8,15,12),new Product("PRD-004","SKU-APP-002","Running Shorts — Navy",Category.APPAREL,new BigDecimal("39.99"),55,20,2),new Product("PRD-005","SKU-HOME-001","Ceramic Pour-Over Set",Category.HOME,new BigDecimal("49.99"),22,10,4),new Product("PRD-006","SKU-HOME-002","LED Desk Lamp — Dimmable",Category.HOME,new BigDecimal("59.99"),0,15,0),new Product("PRD-007","SKU-ELEC-003","Portable Charger 20K",Category.ELECTRONICS,new BigDecimal("44.99"),18,25,8),new Product("PRD-008","SKU-APP-003","Hoodie — Heather Grey",Category.APPAREL,new BigDecimal("54.99"),11,12,15)));};} }
