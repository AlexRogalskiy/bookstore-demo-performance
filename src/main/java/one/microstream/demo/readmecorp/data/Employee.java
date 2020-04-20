
package one.microstream.demo.readmecorp.data;

public interface Employee extends NamedWithAddress
{
	public static Employee New(
		final String name,
		final Address address
	)
	{
		return new Default(name, address);
	}
	
	public static class Default extends NamedWithAddress.Abstract implements Employee
	{
		public Default(
			final String name,
			final Address address
		)
		{
			super(name, address);
		}
		
	}
	
}
