## Welcome to ActiveORM

ActiveORM is a Java ORM framework for Mysql. 


## Getting Started

1. download it

```java
   git clone git://github.com/allwefantasy/active_orm.git
```

2. intergrated to your application. 



```java
       // Actually this means you should put your mongo configuration in a yaml file.And then load it.

        InputStream inputStream = Main.class.getResourceAsStream("application_for_test.yml");
        Settings settings = InternalSettingsPreparer.simplePrepareSettings(ImmutableSettings.Builder.EMPTY_SETTINGS,
                inputStream);

        //when settings have been build ,now we can configure ActiveORM
        JPA.CSDNORMConfiguration csdnormConfiguration = new JPA.CSDNORMConfiguration("development", settings, Main.class);
        JPA.configure(csdnormConfiguration);
        //Everything is done.Now free to use! 
        Tag.findAll();
       
        
   
``` 

3 configuration file demo

```java
development:
    datasources:
       mysql:
           host: 127.0.0.1
           port: 3306
           database: wow
           username: root
           password: csdn.net
           disable: false

application:
    model:   com.example.model
    
       
```


4 create tables , models and using them.

first step,create some tables;

```sql
--标签表
CREATE TABLE `tag` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `tag_synonym_id` int(11) DEFAULT NULL,
  `weight` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB  DEFAULT CHARSET=utf8;

--标签组。一个标签可以属于多个标签组。一个标签组包含多个标签
CREATE TABLE `tag_group` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `id` (`id`)
) ENGINE=InnoDB  DEFAULT CHARSET=utf8;

--博客和标签的关联表。存有 博客id和标签id
CREATE TABLE `blog_tag` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `tag_id` int(11) DEFAULT NULL,
  `object_id` int(11) DEFAULT NULL,
  `created_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--标签近义词组。一个标签只可能属于一个标签近义词
CREATE TABLE `tag_synonym` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

```

now create models

```java

public class Tag extends Model {
    @Validate
    private final static Map $name = map(
    presence, map("message", "{}字段不能为空"),
    uniqueness, map("message", "{}字段不能重复")
    );

    @OneToMany
    private List<BlogTag> blog_tags = list();

    @ManyToMany
    private List<TagGroup> tag_groups = list();
}



public class BlogTag extends Model {

    @ManyToOne
    private Tag tag;
}


public class TagGroup extends Model {
    @ManyToMany
    private List<Tag> tags = list();
}


public class TagSynonym extends Model {
    @OneToMany
    private List<Tag> tags = list();
}
```


That's all!!! Proerties in Model is Optinal. The only thing is to write validators and declare relation .

Now we can use all these Models;

you can query a  tag object like this:
```
Tag tag = Tag.find(17);
String name = tag.attr("name",String.class);
```

if you wanna assingh a value to name,you can do it like this:

```java
tag.attr("name","jack");
```

Of course, you also can define 'name' property in Tag Model.

if you have a tagGroup,now wanna add a tag to tagGroup.Do like follows:

```java
tagGroup.associate("tags").add(tag);
```


####Association

ActiveORM support three kind of Relations.

* OneToOne
* OneToMany
* ManyToMany

Assume you have tagGroup object,now wanna add a tag,do like this：

```java
tagGroup.associate("tags").add(tag);
```

remove their relationship like this:

```java
tagGroup.associate("tags").remove(tag);
```
you can also define a empty method like this in TagGroup class:

```java
public class TagGroup extends Model {
    @OneToMany
    private List<Tag> tags = list();
    public Association tags(){throw new AutoGeneration();}
}
```

now you can do stuff like this:

```java
tagGroup.tags().add(tag);
//or
tagGroup.tags().remove(tag);
//query
List<Tag> tags = tagSynonym.tags().where("id>10").fetch(); 
```

ActiveORM query is powerfull.

1.1 find by id

```java

Tag.findById(10)
//或者
Tag.find(10)

```

1.2 find by ids

```java
Tag.find(list(1,2,,4,5))
```
1.3 where condition query

```java
Tag.where("id=:id",map("id",7)).fetch();
```


more complex example:


```java
Tag.where("tag_synonym=:tag_synonym",map("tag_synonym",tag_synonym));
```


1.4 order

```java
Tag.order("id desc")


Tag.order("id desc,name asc")
```

1.5 joins


```java
Tag.joins("tag_synonym").fetch();
```


you also can join many models at one time:

```java
Tag.joins("tag_synonym left join  tag_groups left join blog_tags").fetch();
```






