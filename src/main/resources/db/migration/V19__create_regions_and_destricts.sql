CREATE TABLE regions (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         external_id VARCHAR(100) NOT NULL UNIQUE,
                         name VARCHAR(100) NOT NULL,
                         slug VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE districts (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           external_id VARCHAR(100) NOT NULL UNIQUE,
                           name VARCHAR(100) NOT NULL,
                           slug VARCHAR(100) NOT NULL,
                           region_id UUID NOT NULL,

                           CONSTRAINT fk_district_region
                               FOREIGN KEY (region_id)
                                   REFERENCES regions(id),

                           CONSTRAINT uk_district_region_name
                               UNIQUE (region_id, name)
);

CREATE INDEX idx_district_region_id
    ON districts(region_id);