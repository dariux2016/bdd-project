CREATE TABLE IF NOT EXISTS customers (
    id      integer PRIMARY KEY,
    name    text NOT NULL,
    vip     boolean NOT NULL DEFAULT false,
    profile jsonb
);

CREATE TABLE IF NOT EXISTS orders (
    id          serial PRIMARY KEY,
    customer_id integer NOT NULL REFERENCES customers (id),
    status      text NOT NULL,
    total       numeric(10, 2) NOT NULL,
    placed_at   timestamptz NOT NULL,
    shipped_on  date
);
