CREATE TABLE customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    customer_number VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_customers_user_id
        UNIQUE (user_id),

    CONSTRAINT uk_customers_customer_number
         UNIQUE (customer_number),

    CONSTRAINT fk_customers_user
         FOREIGN KEY (user_id)
         REFERENCES users(id)
);

CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    wallet_number VARCHAR(30) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL ,

    CONSTRAINT uk_wallets_customer_id
           UNIQUE (customer_id),

    CONSTRAINT uk_wallets_wallet_number
            UNIQUE (wallet_number),

    CONSTRAINT fk_wallets_customer
            FOREIGN KEY (customer_id)
            REFERENCES customers(id)

);

CREATE TABLE financial_accounts (
  id UUID PRIMARY KEY,
  wallet_id UUID NOT NULL,
  account_number VARCHAR(30) NOT NULL,
  account_type VARCHAR(30) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  current_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

     CONSTRAINT uk_financial_accounts_wallets_id
        UNIQUE (wallet_id),

     CONSTRAINT uk_financial_accounts_account_number
         UNIQUE (account_number),

     CONSTRAINT fk_financial_accounts_wallet
         FOREIGN KEY (wallet_id)
         REFERENCES wallets(id),

     CONSTRAINT CHK_financial_accounts_balance
         CHECK ( current_balance >= 0 )
);