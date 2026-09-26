package net.csdn.ormtest.pg;

import net.csdn.jpa.model.Model;

public class Customer extends Model {
    private Integer id;
    private String name;

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
