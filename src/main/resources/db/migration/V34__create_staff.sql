CREATE TABLE staff (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    staff_no VARCHAR(30) NOT NULL UNIQUE,
    job_title VARCHAR(100) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    middle_name VARCHAR(100),
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(30) NOT NULL,
    created_by UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_staff_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);
