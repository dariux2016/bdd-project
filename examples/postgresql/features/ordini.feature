# language: it
Funzionalità: Ordini e clienti in PostgreSQL

  Contesto:
    Dato il file di script "scripts/schema.sql" viene eseguito sulla sorgente PostgreSQL "shop"
    E la tabella "orders" della sorgente PostgreSQL "shop" viene svuotata
    E la tabella "customers" della sorgente PostgreSQL "shop" viene svuotata

  @smoke
  Scenario: Righe da tabella e verifiche con filtro
    Dato queste righe vengono inserite nella tabella "customers" della sorgente PostgreSQL "shop"
      | id | name | vip   |
      | 1  | Ada  | true  |
      | 2  | Bob  | false |
    Allora esiste una riga nella tabella "customers" della sorgente PostgreSQL "shop"
      | campo | operatore | valore |
      | name  | uguale    | Ada    |
    E esistono esattamente 2 righe nella tabella "customers" della sorgente PostgreSQL "shop"
    E non esiste alcuna riga nella tabella "customers" della sorgente PostgreSQL "shop"
      | campo | operatore | valore |
      | name  | uguale    | Zed    |

  Scenario: Query nativa
    Dato queste righe vengono inserite nella tabella "customers" della sorgente PostgreSQL "shop"
      | id | name |
      | 1  | Ada  |
    Allora la query "SELECT count(*) AS n FROM customers" sulla sorgente PostgreSQL "shop" restituisce una riga
      | campo | operatore | valore |
      | n     | uguale    | 1      |
